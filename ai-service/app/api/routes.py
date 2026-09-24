import json
from collections.abc import Iterator
from itertools import chain
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import StreamingResponse

from app.container import Services
from app.core.correlation import correlation_id_var
from app.core.errors import ErrorCode, ServiceError
from app.core.security import require_api_key
from app.core.tracking import track
from app.models.schemas import (
    ChatRequest,
    ChatResponse,
    ClassificationResponse,
    ClassifyRequest,
    ComponentHealth,
    DeleteDocumentResponse,
    EmbedRequest,
    EmbedResponse,
    ErrorResponse,
    HealthResponse,
    SearchRequest,
    SearchResponse,
    SearchResult,
    SentimentRequest,
    SentimentResponse,
    SuggestRequest,
    SuggestResponse,
    SummarizeRequest,
    SummarizeResponse,
)
from app.services.chat import DeltaEvent, DoneEvent, MetaEvent, PipelineEvent

SERVICE_VERSION = "1.0.0"

_ERRORS = {code: {"model": ErrorResponse} for code in (401, 422, 500, 502, 503)}

router = APIRouter(prefix="/api/v1")
protected = APIRouter(prefix="/api/v1", dependencies=[Depends(require_api_key)], responses=_ERRORS)


def services(request: Request) -> Services:
    return request.app.state.services


# Los endpoints son síncronos a propósito: FastAPI los ejecuta en su threadpool, así las llamadas
# bloqueantes (LLM, embeddings, PostgreSQL) no detienen el event loop.


@protected.post("/ai/classify", response_model=ClassificationResponse, summary="Intent, category and priority")
def classify(payload: ClassifyRequest, svc: Services = Depends(services)) -> ClassificationResponse:
    with track("classify"):
        c = svc.classification.classify(payload.message)
        return ClassificationResponse(intent=c.intent, category=c.category, priority=c.priority,
                                      confidence=c.confidence, strategy=c.strategy, urgent_language=c.urgent_language)


@protected.post("/ai/sentiment", response_model=SentimentResponse, summary="Sentiment of a message")
def sentiment(payload: SentimentRequest, svc: Services = Depends(services)) -> SentimentResponse:
    with track("sentiment"):
        r = svc.sentiment.analyze(payload.message)
        return SentimentResponse(sentiment=r.sentiment, confidence=r.confidence, score=r.score, strategy=r.strategy)


@protected.post(
    "/ai/chat",
    response_model=ChatResponse,
    summary="RAG answer to a customer message",
    description="With `stream: true` the response is `text/event-stream` with the events `meta`, "
                "`delta` (answer fragments) and `done` (the final ChatResponse, which prevails over the deltas).",
)
def chat(payload: ChatRequest, svc: Services = Depends(services)):
    if not payload.stream:
        with track("chat"):
            return _final(svc.pipeline.run(payload))

    correlation_id = correlation_id_var.get()
    events = svc.pipeline.run(payload)
    # La primera etapa (guardrails, intención y búsqueda) se ejecuta antes de responder: si falla
    # (p. ej. vector store caído) el backend recibe el status HTTP correcto y puede reintentar
    with track("chat_retrieval"):
        first = next(events)
    return StreamingResponse(
        _sse(chain([first], events), correlation_id),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@protected.post("/ai/summarize", response_model=SummarizeResponse, summary="Incremental conversation summary")
def summarize(payload: SummarizeRequest, svc: Services = Depends(services)) -> SummarizeResponse:
    with track("summarize"):
        result = svc.summarizer.summarize(payload.previous_summary, payload.messages)
        return SummarizeResponse(summary=result.summary, strategy=result.strategy,
                                 message_count=len(payload.messages))


@protected.post("/ai/suggest", response_model=SuggestResponse, summary="Draft reply for a human agent")
def suggest(payload: SuggestRequest, svc: Services = Depends(services)) -> SuggestResponse:
    with track("suggest"):
        return svc.suggestions.suggest(payload)


@protected.post("/knowledge/embed", response_model=EmbedResponse, summary="Chunk, embed and index a document")
def embed(payload: EmbedRequest, svc: Services = Depends(services)) -> EmbedResponse:
    if len(payload.content) > svc.settings.max_document_chars:
        raise ServiceError(ErrorCode.INVALID_REQUEST,
                           f"Document exceeds {svc.settings.max_document_chars} characters")
    with track("embed"):
        result = svc.indexer.index(payload.organization_id, payload.document_id, payload.title,
                                   payload.document_type, payload.content)
        return EmbedResponse(document_id=payload.document_id, chunk_count=result.chunk_count,
                             embedding_model=result.embedding_model, dimensions=result.dimensions,
                             duration_ms=result.duration_ms)


@protected.delete("/knowledge/{document_id}", response_model=DeleteDocumentResponse,
                  summary="Remove a document from the vector store")
def delete_document(document_id: UUID, organization_id: UUID = Query(...),
                    svc: Services = Depends(services)) -> DeleteDocumentResponse:
    with track("delete_document"):
        deleted = svc.indexer.delete(organization_id, document_id)
        return DeleteDocumentResponse(document_id=document_id, deleted_chunks=deleted)


@protected.post("/knowledge/search", response_model=SearchResponse, summary="Semantic search in the knowledge base")
def search(payload: SearchRequest, svc: Services = Depends(services)) -> SearchResponse:
    with track("search"):
        top_k = payload.top_k or svc.settings.vector_search_top_k
        hits = svc.search.search(payload.organization_id, payload.query, top_k)
        return SearchResponse(
            results=[SearchResult(document_id=h.document_id, title=h.title, document_type=h.document_type,
                                  chunk_index=h.chunk_index, content=h.content, score=round(h.score, 4))
                     for h in hits],
            embedding_model=svc.embeddings.model,
        )


@router.get("/health", response_model=HealthResponse, summary="Service health")
def health(svc: Services = Depends(services)) -> HealthResponse:
    store_available = svc.store.is_available()
    return HealthResponse(
        # Sin vector store el servicio sigue clasificando y resumiendo: queda degradado, no caído
        status="UP" if store_available else "DEGRADED",
        service=svc.settings.service_name,
        version=SERVICE_VERSION,
        llm=ComponentHealth(provider="openai-compatible" if svc.llm.enabled else "disabled",
                            available=svc.llm.enabled, model=svc.llm.model),
        embeddings=ComponentHealth(provider=svc.embeddings.provider, available=True, model=svc.embeddings.model),
        vector_store=ComponentHealth(provider=svc.store.name, available=store_available),
        prompts=svc.prompts.catalog(),
    )


def _final(events: Iterator[PipelineEvent]) -> ChatResponse:
    for event in events:
        if isinstance(event, DoneEvent):
            return event.response
    raise RuntimeError("Response pipeline finished without a final response")


def _sse(events: Iterator[PipelineEvent], correlation_id: str) -> Iterator[str]:
    try:
        with track("chat"):
            while True:
                # Starlette ejecuta cada paso del generador en un hilo con una copia nueva del contexto:
                # el correlation ID se fija en cada paso para que llegue a los logs y a la llamada al LLM
                correlation_id_var.set(correlation_id)
                event = next(events, None)
                if event is None:
                    break
                if isinstance(event, MetaEvent):
                    yield _frame("meta", event.data)
                elif isinstance(event, DeltaEvent):
                    yield _frame("delta", {"text": event.text})
                elif isinstance(event, DoneEvent):
                    yield _frame("done", event.response.model_dump(mode="json"))
    except ServiceError as ex:
        yield _frame("error", {"code": ex.code.value, "message": ex.message})


def _frame(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

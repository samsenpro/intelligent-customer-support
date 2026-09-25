import json
import math
from uuid import uuid4

import httpx
import pytest

from app.core.text import detect_language, numbers
from app.embeddings.base import EmbeddingError
from app.embeddings.hashing import HashingEmbeddingService
from app.embeddings.openai_compatible import OpenAICompatibleEmbeddingService
from app.llm.base import LlmError
from app.llm.openai_compatible import OpenAICompatibleLlmService
from app.rag.chunking import TextChunker
from app.rag.ranking import ContextRanker
from app.rag.vector_store.base import ChunkRecord, SearchHit
from app.rag.vector_store.memory import InMemoryVectorStore
from app.services.prompts import PromptTemplateService
from app.services.validation import Issue, ResponseValidator
from tests.conftest import ORG_A, ORG_B, REFUND_POLICY

# ---------------------------------------------------------------- texto y chunking


def test_language_detection_and_numbers():
    assert detect_language("¿Dónde está mi pedido?") == "es"
    assert detect_language("Where is my order?") == "en"
    assert numbers("Cuesta $45.000 y llega en 3 días (1,5%)") == {"45000", "3", "15"}


def test_chunks_respect_the_maximum_size_and_overlap():
    text = " ".join(f"Esta es la frase número {i} del manual del producto." for i in range(40))
    chunks = TextChunker(max_chars=300, overlap_chars=80).split(text)
    assert len(chunks) > 3
    assert all(len(c.content) <= 300 for c in chunks)
    assert [c.index for c in chunks] == list(range(len(chunks)))
    # La última frase de un fragmento se repite al inicio del siguiente
    last_sentence = chunks[0].content.split(". ")[-1]
    assert chunks[1].content.startswith(last_sentence.rstrip("."))


def test_markdown_headings_are_attached_to_their_section():
    chunks = TextChunker(max_chars=200, overlap_chars=0).split(REFUND_POLICY)
    assert chunks[0].content.startswith("Política de reembolsos: Puedes solicitar")
    assert any(c.content.startswith("Plazos: Una vez aprobado") for c in chunks)


def test_sentences_longer_than_a_chunk_are_split_by_words():
    chunks = TextChunker(max_chars=200, overlap_chars=20).split("palabra " * 100)
    assert all(len(c.content) <= 200 for c in chunks)


# ---------------------------------------------------------------- embeddings


def test_hashing_embeddings_are_deterministic_normalized_and_lexically_similar():
    embeddings = HashingEmbeddingService(dimensions=256)
    refund, refund_again, shipping = embeddings.embed_documents(
        ["plazo del reembolso", "¿Cuál es el plazo de los reembolsos?", "horario de la tienda física"])
    assert refund == embeddings.embed_query("plazo del reembolso")
    assert math.isclose(sum(v * v for v in refund), 1.0, rel_tol=1e-9)
    cosine = lambda a, b: sum(x * y for x, y in zip(a, b, strict=True))  # noqa: E731
    assert cosine(refund, refund_again) > cosine(refund, shipping)


def _embedding_server(dimensions: int, seen: list[dict]):
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen.append(body)
        data = [{"index": i, "embedding": [0.1] * dimensions} for i in range(len(body["input"]))][::-1]
        return httpx.Response(200, json={"data": data})
    return httpx.Client(base_url="http://embeddings", transport=httpx.MockTransport(handler))


def test_openai_compatible_embeddings_batch_and_prefix_requests():
    seen: list[dict] = []
    service = OpenAICompatibleEmbeddingService("http://embeddings", "nomic-embed-text", 4, batch_size=2,
                                               query_prefix="search_query: ", document_prefix="search_document: ",
                                               client=_embedding_server(4, seen))
    vectors = service.embed_documents(["a", "b", "c"])
    assert len(vectors) == 3
    assert [len(call["input"]) for call in seen] == [2, 1]
    assert seen[0]["input"][0] == "search_document: a"
    service.embed_query("hola")
    assert seen[-1]["input"] == ["search_query: hola"]


def test_openai_compatible_embeddings_reject_unexpected_dimensions():
    service = OpenAICompatibleEmbeddingService("http://embeddings", "model", 8, client=_embedding_server(4, []))
    with pytest.raises(EmbeddingError, match="4 dimensions"):
        service.embed_query("hola")


# ---------------------------------------------------------------- vector store y ranking


def test_memory_store_isolates_organizations_and_embedding_models():
    store = InMemoryVectorStore()
    document_id = uuid4()
    store.replace_document(ORG_A, document_id, [ChunkRecord(ORG_A, document_id, 0, "T", "FAQ", "texto", [1.0, 0.0])],
                           "model-a")
    assert store.search(ORG_A, [1.0, 0.0], "model-a", 5)[0].score == pytest.approx(1.0)
    assert store.search(ORG_B, [1.0, 0.0], "model-a", 5) == []
    # Vectores de otro modelo no son comparables: no se mezclan
    assert store.search(ORG_A, [1.0, 0.0], "model-b", 5) == []


def test_ranker_filters_irrelevant_chunks_deduplicates_and_limits_context():
    doc = uuid4()
    hits = [
        SearchHit(doc, "Reembolsos", "POLICY", 0, "El reembolso tarda 10 días.", 0.60),
        SearchHit(doc, "Reembolsos", "POLICY", 1, "El reembolso tarda 10 días.", 0.58),
        SearchHit(doc, "Envíos", "POLICY", 0, "x" * 500, 0.50),
        SearchHit(doc, "Horario", "FAQ", 0, "Abrimos a las 8.", 0.05),
    ]
    ranked = ContextRanker(min_score=0.1, max_context_chars=400).rank("¿cuánto tarda el reembolso?", hits, top_k=4)
    assert [c.hit.chunk_index for c in ranked.chunks] == [0]
    assert ranked.best_score == pytest.approx(0.60)
    assert "[1] Reembolsos (POLICY)" in ranked.format()


# ---------------------------------------------------------------- validación y prompts


def test_validator_detects_invented_numbers_leaks_and_low_grounding():
    validator = ResponseValidator()
    context = "El reembolso se acredita en 5 a 10 días hábiles."
    system = "Use ONLY the information in the CONTEXT section and never invent prices or deadlines."

    ok = validator.validate("El reembolso se acredita en 5 a 10 días hábiles.", context=context, conversation="",
                            system_prompt=system)
    assert ok.valid and ok.grounding > 0.8

    invented = validator.validate("Tarda 2 días y cuesta $9.900.", context=context, conversation="",
                                  system_prompt=system)
    assert Issue.UNGROUNDED_NUMBERS in invented.issues and not invented.valid

    from_conversation = validator.validate("Tu pedido 4521 se reembolsa en 5 a 10 días hábiles.", context=context,
                                           conversation="Customer: mi pedido 4521", system_prompt=system)
    assert from_conversation.valid

    leak = validator.validate("Use ONLY the information in the CONTEXT section and never invent prices.",
                              context=context, conversation="", system_prompt=system)
    assert Issue.PROMPT_LEAK in leak.issues

    off_topic = validator.validate("Nuestro equipo de fútbol ganó el campeonato mundial.", context=context,
                                   conversation="", system_prompt=system)
    assert Issue.LOW_GROUNDING in off_topic.issues and off_topic.valid  # solo reduce la confianza


def test_prompt_templates_are_versioned_and_values_are_not_reinterpreted():
    prompts = PromptTemplateService()
    catalog = prompts.catalog()
    assert set(catalog) == {"customer_response", "agent_suggestion", "summarization", "classification", "sentiment"}
    assert catalog["customer_response"]["versions"] == ["v1", "v2"]

    rendered = prompts.render("customer_response", "v1", organization_name="Acme", language="Spanish",
                              summary="", history="", context="ctx", question="$organization_name ${context}")
    assert rendered.id == "customer_response_v1"
    assert "$organization_name ${context}" in rendered.messages[1]["content"]

    pinned = PromptTemplateService(overrides={"customer_response": "v1"})
    assert pinned.active_version("customer_response") == "v1"
    with pytest.raises(ValueError):
        PromptTemplateService(overrides={"customer_response": "v9"})


# ---------------------------------------------------------------- cliente LLM


def _llm_server(handler) -> OpenAICompatibleLlmService:
    client = httpx.Client(base_url="http://llm", transport=httpx.MockTransport(handler))
    return OpenAICompatibleLlmService("http://llm", "test-model", client=client)


def test_llm_client_completes_and_streams():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert request.headers["X-Correlation-Id"]
        if not body["stream"]:
            return httpx.Response(200, json={"choices": [{"message": {"content": "hola"}}]})
        chunks = [{"choices": [{"delta": {"content": part}}]} for part in ("Ho", "la", "!")]
        sse = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"
        return httpx.Response(200, text=sse, headers={"content-type": "text/event-stream"})

    llm = _llm_server(handler)
    messages = [{"role": "user", "content": "hi"}]
    assert llm.complete(messages, operation="test") == "hola"
    assert list(llm.stream(messages, operation="test")) == ["Ho", "la", "!"]


def test_llm_client_errors_are_llm_errors():
    llm = _llm_server(lambda request: httpx.Response(503))
    with pytest.raises(LlmError, match="HTTP 503"):
        llm.complete([{"role": "user", "content": "hi"}], operation="test")
    with pytest.raises(LlmError):
        list(llm.stream([{"role": "user", "content": "hi"}], operation="test"))
    empty = _llm_server(lambda request: httpx.Response(200, json={"choices": [{"message": {"content": " "}}]}))
    with pytest.raises(LlmError, match="empty"):
        empty.complete([{"role": "user", "content": "hi"}], operation="test")

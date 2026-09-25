import logging
import time
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

from app.core.metrics import RAG_OUTCOMES
from app.core.text import content_stems, detect_language
from app.llm.base import LlmError, LlmService
from app.models.schemas import (
    ChatRequest,
    ChatResponse,
    ChatStatus,
    ClassificationResponse,
    Handoff,
    NoContextAction,
    Source,
    Validation,
)
from app.rag.ranking import ContextRanker, RankedContext
from app.rag.retrieval import VectorSearchService
from app.services.classification.service import ClassificationService
from app.services.classification.taxonomy import Category, Intent, Priority
from app.services.conversation_context import ConversationContextBuilder
from app.services.extractive import ExtractiveAnswerBuilder
from app.services.guardrails import InputGuard
from app.services.messages import Text, text
from app.services.prompts import PromptTemplateService, RenderedPrompt
from app.services.validation import NO_CONTEXT_SENTINEL, Issue, ResponseValidator

logger = logging.getLogger(__name__)

LANGUAGE_NAMES = {"es": "Spanish", "en": "English"}
# Rango de similitud (por encima del mínimo) que se considera una coincidencia plena
_RETRIEVAL_SPAN = 0.35


@dataclass(frozen=True, slots=True)
class MetaEvent:
    data: dict[str, Any]


@dataclass(frozen=True, slots=True)
class DeltaEvent:
    text: str


@dataclass(frozen=True, slots=True)
class DoneEvent:
    response: ChatResponse


PipelineEvent = MetaEvent | DeltaEvent | DoneEvent


def confidence_score(best_score: float, min_score: float, grounding: float) -> float:
    """Confianza de una respuesta RAG: cuánto se parece el mejor fragmento a la pregunta (por encima
    del umbral de relevancia) y cuánto de la respuesta está respaldado por el contexto."""
    retrieval = min(max((best_score - min_score) / _RETRIEVAL_SPAN, 0.0), 1.0)
    return round(0.3 + 0.7 * (0.65 * retrieval + 0.35 * grounding), 3)


class ResponsePipeline:
    """Pipeline de respuesta de la IA al cliente. Cada etapa es un servicio independiente:

    User Message -> Input guardrails -> Intent Detection -> Conversation Context -> Embedding ->
    Vector Search -> Context Ranking -> Prompt Construction -> LLM -> Response Validation -> Final Response

    Es un generador de eventos: el endpoint con streaming reenvía cada fragmento al backend a medida
    que el LLM lo genera y el endpoint normal solo se queda con el evento final.
    """

    def __init__(
        self,
        *,
        guard: InputGuard,
        classifier: ClassificationService,
        context_builder: ConversationContextBuilder,
        search: VectorSearchService,
        ranker: ContextRanker,
        prompts: PromptTemplateService,
        llm: LlmService,
        validator: ResponseValidator,
        extractive: ExtractiveAnswerBuilder,
        default_top_k: int,
        candidate_multiplier: int,
    ) -> None:
        self.guard = guard
        self.classifier = classifier
        self.context_builder = context_builder
        self.search = search
        self.ranker = ranker
        self.prompts = prompts
        self.llm = llm
        self.validator = validator
        self.extractive = extractive
        self.default_top_k = default_top_k
        self.candidate_multiplier = candidate_multiplier

    def run(self, request: ChatRequest) -> Iterator[PipelineEvent]:
        started = time.perf_counter()
        language = detect_language(request.message)
        settings = request.settings

        # 1. Guardrails de entrada: los intentos de prompt injection no llegan al LLM
        verdict = self.guard.check(request.message)
        if verdict.blocked:
            logger.warning("Message blocked by input guardrails", extra={"reason": verdict.reason})
            blocked = ClassificationResponse(intent=Intent.GENERAL_QUESTION, category=Category.GENERAL,
                                             priority=Priority.MEDIUM, confidence=1.0, strategy="guardrail")
            yield self._done(started, ChatStatus.BLOCKED, text(Text.BLOCKED, language), 0.9, blocked, language)
            return
        intent = self._intent(request)

        # 2. Intención: petición explícita de un humano o saludo no necesitan RAG
        if intent.intent is Intent.HUMAN_REQUEST:
            yield self._done(started, ChatStatus.AI_HANDOFF_REQUESTED, text(Text.HANDOFF, language), intent.confidence,
                             intent, language, handoff_reason="CUSTOMER_REQUESTED_HUMAN")
            return
        if intent.intent is Intent.GREETING and len(content_stems(request.message)) <= 2:
            answer = text(Text.GREETING, language, organization=request.organization_name)
            yield from self._emit(answer)
            yield self._done(started, ChatStatus.ANSWERED, answer, 0.9, intent, language)
            return

        # 3. Contexto de la conversación (memoria: resumen + últimos N mensajes)
        context = self.context_builder.build(request.message, request.history, request.summary)

        # 4-6. Embedding de la consulta, búsqueda vectorial (solo en la organización) y ranking
        top_k = settings.top_k or self.default_top_k
        query = self.context_builder.retrieval_query(request.message, context)
        hits = self.search.search(request.organization_id, query, top_k * self.candidate_multiplier)
        ranked = self.ranker.rank(query, hits, top_k)
        sources = _sources(ranked)
        yield MetaEvent({"intent": intent.model_dump(mode="json"), "language": language,
                         "sources": [s.model_dump(mode="json") for s in sources]})
        if ranked.empty:
            yield self._no_context(started, settings.no_context_action, intent, language, None, None)
            return
        # Si ni con una respuesta totalmente respaldada se alcanzaría el umbral, no se llama al LLM:
        # se deriva directamente (y el cliente no ve un borrador que después se descartaría)
        best_possible = confidence_score(ranked.best_score, self.ranker.min_score, 1.0)
        if best_possible < settings.confidence_threshold:
            yield self._done(started, ChatStatus.AI_HANDOFF_REQUESTED, text(Text.HANDOFF, language), best_possible,
                             intent, language, sources=sources, handoff_reason="LOW_CONFIDENCE")
            return

        # 7. Construcción del prompt (plantilla versionada)
        prompt = self.prompts.render(
            "customer_response",
            settings.prompt_version,
            organization_name=request.organization_name,
            language=LANGUAGE_NAMES.get(language, "Spanish"),
            summary=context.summary or "(none)",
            history=context.history_text() or "(none)",
            context=ranked.format(),
            question=request.message,
        )

        # 8. LLM (con streaming). Si falla, respuesta extractiva: el cliente nunca se queda sin respuesta
        answer: str | None = None
        model = "extractive"
        degraded = False
        emitted = False
        if self.llm.enabled:
            full: list[str] = []
            try:
                for delta in self._llm_deltas(prompt, full):
                    emitted = True
                    yield DeltaEvent(delta)
                answer = "".join(full).strip()
                model = self.llm.model or "llm"
            except LlmError as ex:
                logger.warning("LLM failed, falling back to an extractive answer: %s", ex)
                degraded = True

        # 9. Validación de la respuesta
        validation = None
        grounding = 1.0
        if answer is not None:
            validation = self.validator.validate(
                answer,
                context=ranked.format(),
                conversation=f"{context.conversation_text()}\n{request.message}",
                system_prompt=prompt.messages[0]["content"],
            )
            if Issue.NO_CONTEXT in validation.issues:
                yield self._no_context(started, settings.no_context_action, intent, language, model, prompt.id)
                return
            if validation.valid:
                grounding = validation.grounding
            else:
                logger.warning("LLM answer rejected by validation", extra={"issues": list(validation.issues)})
                answer = None
                model = "extractive"
        if answer is None:
            answer = self.extractive.build(request.message, ranked, language)
            if not emitted:
                yield from self._emit(answer)

        # 10. Respuesta final: por debajo del umbral de confianza se propone derivar a un humano
        confidence = confidence_score(ranked.best_score, self.ranker.min_score, grounding)
        status, reason = ChatStatus.ANSWERED, None
        if confidence < settings.confidence_threshold:
            status, reason = ChatStatus.AI_HANDOFF_REQUESTED, "LOW_CONFIDENCE"
        yield self._done(started, status, answer, confidence, intent, language, sources=sources, model=model,
                         prompt_version=prompt.id, validation=validation, degraded=degraded, handoff_reason=reason)

    # ------------------------------------------------------------------ etapas auxiliares

    def _intent(self, request: ChatRequest) -> ClassificationResponse:
        if request.intent_hint:
            hint = request.intent_hint
            return ClassificationResponse(intent=hint.intent, category=hint.category, priority=hint.priority,
                                          confidence=hint.confidence, strategy=hint.strategy)
        c = self.classifier.classify(request.message)
        return ClassificationResponse(intent=c.intent, category=c.category, priority=c.priority,
                                      confidence=c.confidence, strategy=c.strategy, urgent_language=c.urgent_language)

    def _llm_deltas(self, prompt: RenderedPrompt, full: list[str]) -> Iterator[str]:
        """Fragmentos del LLM para el cliente. El inicio se retiene mientras pueda ser el marcador
        NO_RELEVANT_CONTEXT: el cliente nunca debe verlo."""
        held = ""
        releasing = False
        for delta in self.llm.stream(prompt.messages, operation="customer_response"):
            full.append(delta)
            if releasing:
                yield delta
                continue
            held += delta
            probe = held.lstrip()
            if NO_CONTEXT_SENTINEL.startswith(probe) or probe.startswith(NO_CONTEXT_SENTINEL):
                continue
            releasing = True
            yield held

    @staticmethod
    def _emit(answer: str, words_per_chunk: int = 4) -> Iterator[DeltaEvent]:
        words = answer.split(" ")
        for i in range(0, len(words), words_per_chunk):
            chunk = " ".join(words[i : i + words_per_chunk])
            yield DeltaEvent(chunk if i == 0 else " " + chunk)

    def _no_context(self, started: float, action: NoContextAction, intent: ClassificationResponse, language: str,
                    model: str | None, prompt_version: str | None) -> DoneEvent:
        key = {
            NoContextAction.ASK_MORE_INFO: Text.NO_CONTEXT_ASK_MORE_INFO,
            NoContextAction.INFORM: Text.NO_CONTEXT_INFORM,
            NoContextAction.HANDOFF: Text.HANDOFF,
        }[action]
        return self._done(started, ChatStatus.NO_RELEVANT_CONTEXT, text(key, language), 0.0, intent, language,
                          model=model or "none", prompt_version=prompt_version,
                          handoff_reason="NO_RELEVANT_CONTEXT" if action is NoContextAction.HANDOFF else None)

    @staticmethod
    def _done(started: float, status: ChatStatus, answer: str, confidence: float, intent: ClassificationResponse,
              language: str, *, sources: list[Source] | None = None, model: str = "none",
              prompt_version: str | None = None, validation=None, degraded: bool = False,
              handoff_reason: str | None = None) -> DoneEvent:
        RAG_OUTCOMES.labels(status=status.value).inc()
        return DoneEvent(ChatResponse(
            status=status,
            answer=answer,
            confidence=round(min(max(confidence, 0.0), 1.0), 3),
            intent=intent,
            language=language,
            sources=sources or [],
            handoff=Handoff(requested=handoff_reason is not None, reason=handoff_reason),
            model=model,
            prompt_version=prompt_version,
            validation=Validation(valid=validation.valid, issues=[i.value for i in validation.issues],
                                  grounding=validation.grounding) if validation else None,
            degraded=degraded,
            latency_ms=int((time.perf_counter() - started) * 1000),
        ))


def _sources(ranked: RankedContext) -> list[Source]:
    return [
        Source(document_id=c.hit.document_id, title=c.hit.title, document_type=c.hit.document_type,
               chunk_index=c.hit.chunk_index, score=round(c.hit.score, 4))
        for c in ranked.chunks
    ]

import logging

from app.core.text import detect_language
from app.llm.base import LlmError, LlmService
from app.models.schemas import Source, SuggestRequest, SuggestResponse
from app.rag.ranking import ContextRanker
from app.rag.retrieval import VectorSearchService
from app.services.chat import LANGUAGE_NAMES, confidence_score
from app.services.conversation_context import ConversationContextBuilder
from app.services.extractive import ExtractiveAnswerBuilder
from app.services.messages import Text, text
from app.services.prompts import PromptTemplateService
from app.services.validation import ResponseValidator

logger = logging.getLogger(__name__)


class SuggestionService:
    """Borrador de respuesta para un agente humano: analiza la conversación, el último mensaje del
    cliente y la base de conocimiento. Nunca se envía solo: el agente lo acepta, edita o rechaza."""

    def __init__(
        self,
        *,
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
        self.context_builder = context_builder
        self.search = search
        self.ranker = ranker
        self.prompts = prompts
        self.llm = llm
        self.validator = validator
        self.extractive = extractive
        self.default_top_k = default_top_k
        self.candidate_multiplier = candidate_multiplier

    def suggest(self, request: SuggestRequest) -> SuggestResponse:
        language = detect_language(request.customer_message)
        context = self.context_builder.build(request.customer_message, request.history, request.summary)
        top_k = request.top_k or self.default_top_k
        query = self.context_builder.retrieval_query(request.customer_message, context)
        hits = self.search.search(request.organization_id, query, top_k * self.candidate_multiplier)
        ranked = self.ranker.rank(query, hits, top_k)

        if ranked.empty:
            return SuggestResponse(suggested_response=text(Text.SUGGESTION_NO_CONTEXT, language), confidence=0.3,
                                   sources=[], has_context=False, model="template", prompt_version=None,
                                   language=language)

        prompt = self.prompts.render(
            "agent_suggestion",
            organization_name=request.organization_name,
            language=LANGUAGE_NAMES.get(language, "Spanish"),
            summary=context.summary or "(none)",
            history=context.history_text() or "(none)",
            context=ranked.format(),
            question=request.customer_message,
        )
        draft, model, grounding = None, "extractive", 1.0
        if self.llm.enabled:
            try:
                draft = self.llm.complete(prompt.messages, operation="agent_suggestion").strip()
                validation = self.validator.validate(
                    draft, context=ranked.format(),
                    conversation=f"{context.conversation_text()}\n{request.customer_message}",
                    system_prompt=prompt.messages[0]["content"],
                )
                if validation.valid:
                    model, grounding = self.llm.model or "llm", validation.grounding
                else:
                    logger.warning("Suggestion rejected by validation", extra={"issues": list(validation.issues)})
                    draft = None
            except LlmError as ex:
                logger.warning("LLM suggestion failed, using extractive draft: %s", ex)
                draft = None
        if draft is None:
            draft = self.extractive.build(request.customer_message, ranked, language)

        sources = [
            Source(document_id=c.hit.document_id, title=c.hit.title, document_type=c.hit.document_type,
                   chunk_index=c.hit.chunk_index, score=round(c.hit.score, 4))
            for c in ranked.chunks
        ]
        return SuggestResponse(
            suggested_response=draft,
            confidence=confidence_score(ranked.best_score, self.ranker.min_score, grounding),
            sources=sources, has_context=True, model=model, prompt_version=prompt.id, language=language,
        )

import logging
from dataclasses import dataclass

from app.core.text import detect_language, truncate
from app.llm.base import LlmError, LlmService
from app.models.schemas import HistoryMessage, SenderRole
from app.services.chat import LANGUAGE_NAMES
from app.services.prompts import PromptTemplateService

logger = logging.getLogger(__name__)

_LABELS = {
    "es": {"asked": "El cliente consultó", "answer": "Última respuesta del equipo",
           "last": "Último mensaje del cliente"},
    "en": {"asked": "The customer asked", "answer": "Last reply from the team", "last": "Last customer message"},
}
_ROLE = {SenderRole.CUSTOMER: "Customer", SenderRole.AI: "Assistant", SenderRole.AGENT: "Agent",
         SenderRole.SYSTEM: "System"}


@dataclass(frozen=True, slots=True)
class SummaryResult:
    summary: str
    strategy: str


class ConversationSummarizer:
    """Resumen incremental de una conversación: el resumen anterior más los mensajes nuevos.

    El backend guarda el resultado y lo envía en cada petición de chat en lugar de los mensajes
    antiguos, así el contexto que llega al LLM (y los tokens) no crece con la conversación.
    """

    def __init__(self, llm: LlmService, prompts: PromptTemplateService, max_input_chars: int = 12_000) -> None:
        self._llm = llm
        self._prompts = prompts
        self._max_input_chars = max_input_chars

    def summarize(self, previous_summary: str | None, messages: list[HistoryMessage]) -> SummaryResult:
        customer_text = " ".join(m.content for m in messages if m.role is SenderRole.CUSTOMER)
        language = detect_language(customer_text or " ".join(m.content for m in messages))
        if self._llm.enabled:
            transcript = "\n".join(f"{_ROLE[m.role]}: {m.content}" for m in messages)
            prompt = self._prompts.render(
                "summarization",
                language=LANGUAGE_NAMES.get(language, "Spanish"),
                previous_summary=previous_summary or "(none)",
                # Se conservan los mensajes más recientes si la transcripción es demasiado larga
                messages=transcript[-self._max_input_chars :],
            )
            try:
                summary = self._llm.complete(prompt.messages, operation="summarization", max_tokens=300).strip()
                if summary:
                    return SummaryResult(summary, "llm")
            except LlmError as ex:
                logger.warning("LLM summarization failed, using extractive summary: %s", ex)
        return SummaryResult(self._extractive(previous_summary, messages, language), "extractive")

    @staticmethod
    def _extractive(previous_summary: str | None, messages: list[HistoryMessage], language: str) -> str:
        labels = _LABELS.get(language, _LABELS["es"])
        parts = [previous_summary.strip()] if previous_summary and previous_summary.strip() else []
        customer = [m.content.strip() for m in messages if m.role is SenderRole.CUSTOMER and m.content.strip()]
        replies = [m.content.strip() for m in messages if m.role in (SenderRole.AI, SenderRole.AGENT)]
        if customer:
            parts.append(f"{labels['asked']}: {truncate(customer[0], 240)}")
        if replies:
            parts.append(f"{labels['answer']}: {truncate(replies[-1], 240)}")
        if len(customer) > 1:
            parts.append(f"{labels['last']}: {truncate(customer[-1], 240)}")
        return "\n".join(parts)

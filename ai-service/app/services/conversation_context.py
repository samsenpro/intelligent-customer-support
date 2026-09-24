from dataclasses import dataclass

from app.core.text import content_stems, truncate
from app.models.schemas import HistoryMessage, SenderRole

_ROLE_LABEL = {
    SenderRole.CUSTOMER: "Customer",
    SenderRole.AI: "Assistant",
    SenderRole.AGENT: "Agent",
    SenderRole.SYSTEM: "System",
}
# Una pregunta con menos términos que esto suele ser una continuación ("¿y cuánto tarda?")
_FOLLOW_UP_MAX_TERMS = 5


@dataclass(frozen=True, slots=True)
class ConversationContext:
    summary: str
    history: list[HistoryMessage]

    def history_text(self) -> str:
        return "\n".join(f"{_ROLE_LABEL[m.role]}: {m.content}" for m in self.history)

    def conversation_text(self) -> str:
        """Todo lo que el cliente y el equipo ya dijeron: las cifras que aparecen aquí se pueden
        repetir en la respuesta aunque no estén en la base de conocimiento."""
        return f"{self.summary}\n{self.history_text()}".strip()


class ConversationContextBuilder:
    """Memoria de la conversación que llega al LLM: el resumen de lo anterior más los últimos N
    mensajes (nunca la conversación completa, que crecería sin límite en tokens)."""

    def __init__(self, max_messages: int = 10, max_chars_per_message: int = 600) -> None:
        self.max_messages = max_messages
        self.max_chars_per_message = max_chars_per_message

    def build(self, message: str, history: list[HistoryMessage], summary: str | None) -> ConversationContext:
        recent = list(history)
        # El backend puede incluir el mensaje actual al final del historial: no se duplica
        if recent and recent[-1].role is SenderRole.CUSTOMER and recent[-1].content.strip() == message.strip():
            recent.pop()
        recent = recent[-self.max_messages :] if self.max_messages else []
        trimmed = [
            HistoryMessage(role=m.role, content=truncate(m.content.strip(), self.max_chars_per_message))
            for m in recent
            if m.content.strip()
        ]
        return ConversationContext((summary or "").strip(), trimmed)

    def retrieval_query(self, message: str, context: ConversationContext) -> str:
        """Consulta para la búsqueda vectorial. Si la pregunta es una continuación corta se le añade
        el mensaje anterior del cliente, que aporta el tema ("reembolso" en "¿y cuánto tarda?")."""
        if len(content_stems(message)) >= _FOLLOW_UP_MAX_TERMS:
            return message
        previous = next((m.content for m in reversed(context.history) if m.role is SenderRole.CUSTOMER), None)
        return f"{previous}\n{message}" if previous else message

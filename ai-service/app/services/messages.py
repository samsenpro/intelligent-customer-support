"""Textos fijos que el asistente envía al cliente, en español e inglés."""

from enum import StrEnum


class Text(StrEnum):
    GREETING = "greeting"
    NO_CONTEXT_ASK_MORE_INFO = "no_context_ask_more_info"
    NO_CONTEXT_INFORM = "no_context_inform"
    HANDOFF = "handoff"
    BLOCKED = "blocked"
    EXTRACTIVE_PREFIX = "extractive_prefix"
    SUGGESTION_NO_CONTEXT = "suggestion_no_context"


_TEXTS: dict[str, dict[Text, str]] = {
    "es": {
        Text.GREETING: "¡Hola! Soy el asistente virtual de {organization}. ¿En qué te puedo ayudar?",
        Text.NO_CONTEXT_ASK_MORE_INFO: (
            "No encontré información sobre eso en nuestra base de conocimiento. ¿Me das más detalles "
            "(producto, número de pedido o qué necesitas exactamente) para ayudarte mejor?"
        ),
        Text.NO_CONTEXT_INFORM: (
            "Lo siento, no tengo información suficiente para responder a eso con seguridad. "
            "Si lo necesitas, puedo comunicarte con un agente."
        ),
        Text.HANDOFF: "Te comunico con un agente de nuestro equipo, que continuará la conversación en breve.",
        Text.BLOCKED: (
            "No puedo ayudarte con esa solicitud. Puedo responder preguntas sobre nuestros productos, "
            "pedidos y políticas."
        ),
        Text.EXTRACTIVE_PREFIX: "Según nuestra información de «{title}»:",
        Text.SUGGESTION_NO_CONTEXT: (
            "Gracias por escribirnos. Para ayudarte mejor, ¿podrías indicarnos más detalles sobre tu caso "
            "(por ejemplo, el número de pedido o el producto)?"
        ),
    },
    "en": {
        Text.GREETING: "Hi! I'm the virtual assistant of {organization}. How can I help you?",
        Text.NO_CONTEXT_ASK_MORE_INFO: (
            "I couldn't find information about that in our knowledge base. Could you give me more details "
            "(product, order number or what exactly you need) so I can help you?"
        ),
        Text.NO_CONTEXT_INFORM: (
            "Sorry, I don't have enough information to answer that reliably. If you need it, "
            "I can connect you with an agent."
        ),
        Text.HANDOFF: "I'm connecting you with an agent from our team, who will continue the conversation shortly.",
        Text.BLOCKED: "I can't help with that request. I can answer questions about our products, orders and policies.",
        Text.EXTRACTIVE_PREFIX: "According to our “{title}”:",
        Text.SUGGESTION_NO_CONTEXT: (
            "Thanks for reaching out. To help you better, could you share more details about your case "
            "(for example, the order number or the product)?"
        ),
    },
}


def text(key: Text, language: str, **values: str) -> str:
    return _TEXTS.get(language, _TEXTS["es"])[key].format(**values)

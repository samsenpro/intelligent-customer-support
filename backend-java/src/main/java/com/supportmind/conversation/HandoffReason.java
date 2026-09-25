package com.supportmind.conversation;

/** Motivo por el que la IA deriva una conversación a un agente humano. */
public enum HandoffReason {
    /** El cliente pidió hablar con una persona. */
    CUSTOMER_REQUESTED_HUMAN,
    /** La confianza de la respuesta quedó por debajo del umbral de la organización. */
    LOW_CONFIDENCE,
    /** La categoría del mensaje está marcada como sensible (siempre la atiende un humano). */
    SENSITIVE_CATEGORY,
    /** La IA no pudo responder varias veces seguidas. */
    REPEATED_FAILED_ANSWERS,
    /** Hay un ticket urgente o el mensaje es urgente. */
    URGENT_TICKET,
    /** No hay información en la base de conocimiento y la organización decidió derivar. */
    NO_RELEVANT_CONTEXT,
    /** El servicio de IA no respondió (circuit breaker, timeouts). */
    AI_UNAVAILABLE,
    /** Un agente tomó la conversación. */
    AGENT_TOOK_OVER
}

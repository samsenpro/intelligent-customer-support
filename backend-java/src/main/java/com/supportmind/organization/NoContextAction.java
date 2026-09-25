package com.supportmind.organization;

/**
 * Qué hace la IA cuando la base de conocimiento no tiene información suficiente
 * ({@code NO_RELEVANT_CONTEXT}).
 */
public enum NoContextAction {
    /** Pide al cliente más detalles. */
    ASK_MORE_INFO,
    /** Deriva la conversación a un agente humano. */
    HANDOFF,
    /** Informa de que no tiene información suficiente. */
    INFORM
}

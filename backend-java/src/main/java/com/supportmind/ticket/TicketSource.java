package com.supportmind.ticket;

public enum TicketSource {
    /** Creado por un agente o por el cliente. */
    MANUAL,
    /** Creado al derivar la conversación de la IA a un humano. */
    AI_HANDOFF,
    /** Creado al clasificar un mensaje que requiere gestión (reembolso, fraude, prioridad alta...). */
    AUTO_CLASSIFICATION
}

package com.supportmind.ai;

public enum SuggestionStatus {
    PENDING,
    /** Enviada tal cual. */
    ACCEPTED,
    /** Enviada con cambios del agente. */
    EDITED,
    REJECTED
}

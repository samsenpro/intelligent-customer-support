package com.supportmind.common;

import java.util.UUID;

/** Referencias resumidas a otras entidades en las respuestas de la API. */
public final class Refs {

    private Refs() {
    }

    public record CustomerRef(UUID id, String fullName, String email) {
    }

    public record AgentRef(UUID id, String displayName) {
    }
}

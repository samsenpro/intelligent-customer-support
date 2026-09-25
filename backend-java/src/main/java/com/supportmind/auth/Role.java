package com.supportmind.auth;

/**
 * Roles de la plataforma (RBAC):
 * <ul>
 *   <li>ADMIN: todo dentro de su organización (usuarios, configuración, base de conocimiento, auditoría).</li>
 *   <li>SUPERVISOR: agentes, tickets, todas las conversaciones y analítica.</li>
 *   <li>AGENT: conversaciones asignadas (y la cola sin asignar), tickets y sugerencias de la IA.</li>
 *   <li>CUSTOMER: solo sus propias conversaciones y tickets.</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    SUPERVISOR,
    AGENT,
    CUSTOMER;

    public String authority() {
        return "ROLE_" + name();
    }

    /** Miembro del equipo de soporte (tiene perfil de agente y puede atender conversaciones). */
    public boolean isStaff() {
        return this != CUSTOMER;
    }

    /** Ve todas las conversaciones de la organización, no solo las asignadas. */
    public boolean seesAllConversations() {
        return this == ADMIN || this == SUPERVISOR;
    }
}

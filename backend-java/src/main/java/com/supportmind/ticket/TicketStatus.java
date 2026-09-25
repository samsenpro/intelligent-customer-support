package com.supportmind.ticket;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING,
    RESOLVED,
    CLOSED;

    private static final Map<TicketStatus, Set<TicketStatus>> TRANSITIONS = Map.of(
            OPEN, EnumSet.of(IN_PROGRESS, WAITING, RESOLVED, CLOSED),
            IN_PROGRESS, EnumSet.of(OPEN, WAITING, RESOLVED, CLOSED),
            WAITING, EnumSet.of(IN_PROGRESS, RESOLVED, CLOSED),
            // Un ticket resuelto se puede reabrir si el problema vuelve; uno cerrado es definitivo
            RESOLVED, EnumSet.of(IN_PROGRESS, CLOSED),
            CLOSED, EnumSet.noneOf(TicketStatus.class));

    public boolean canTransitionTo(TicketStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public boolean isOpen() {
        return this != RESOLVED && this != CLOSED;
    }
}

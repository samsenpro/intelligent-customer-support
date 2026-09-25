package com.supportmind.ticket;

public enum TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT;

    public boolean isHigherThan(TicketPriority other) {
        return ordinal() > other.ordinal();
    }
}

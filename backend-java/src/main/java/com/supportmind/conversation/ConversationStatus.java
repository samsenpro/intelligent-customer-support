package com.supportmind.conversation;

public enum ConversationStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_CUSTOMER,
    RESOLVED,
    CLOSED;

    public boolean isActive() {
        return this != RESOLVED && this != CLOSED;
    }
}

package com.supportmind.exception;

import org.springframework.http.HttpStatus;

/**
 * Códigos de error estables de la API. El cliente decide qué hacer con el código, no con el texto.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request body"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Invalid X-Idempotency-Key header"),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    SIGNUP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "This organization does not accept customer sign-ups"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Route not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered"),
    CUSTOMER_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "A customer with this email already exists"),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "Operation not allowed in the current status"),
    CONVERSATION_CLOSED(HttpStatus.CONFLICT, "The conversation is closed"),
    OPEN_TICKET_ALREADY_EXISTS(HttpStatus.CONFLICT, "The conversation already has an open ticket"),
    SUGGESTION_ALREADY_REVIEWED(HttpStatus.CONFLICT, "The suggestion was already accepted or rejected"),
    AI_REPLY_IN_PROGRESS(HttpStatus.CONFLICT, "The AI is already answering this conversation"),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "A request with this idempotency key is still in progress"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified by another request"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Content-Type"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY,
            "The idempotency key was already used with a different request"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again later"),
    AI_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "AI temporarily unavailable. A human agent can continue the conversation"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required service is temporarily unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

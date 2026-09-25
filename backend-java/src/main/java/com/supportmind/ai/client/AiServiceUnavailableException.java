package com.supportmind.ai.client;

/**
 * Fallo transitorio (servicio caído, timeout, 5xx, 429): se reintenta y cuenta para el circuit breaker.
 */
public class AiServiceUnavailableException extends AiServiceException {

    public AiServiceUnavailableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}

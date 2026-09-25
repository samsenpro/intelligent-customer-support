package com.supportmind.ai.client;

/** Error al llamar al servicio de IA, con el código de error que devolvió (o uno propio). */
public abstract class AiServiceException extends RuntimeException {

    private final String errorCode;

    protected AiServiceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

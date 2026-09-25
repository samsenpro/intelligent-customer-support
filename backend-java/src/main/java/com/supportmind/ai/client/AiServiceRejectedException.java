package com.supportmind.ai.client;

/**
 * El servicio de IA rechazó la petición (4xx) o devolvió una respuesta inválida: reintentar no lo
 * arreglaría y no indica que el servicio esté caído (no abre el circuito).
 */
public class AiServiceRejectedException extends AiServiceException {

    public AiServiceRejectedException(String errorCode, String message) {
        super(errorCode, message, null);
    }
}

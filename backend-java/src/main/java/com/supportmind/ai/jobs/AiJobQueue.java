package com.supportmind.ai.jobs;

/**
 * Cola de trabajos de IA. La lógica de negocio solo publica a través de esta interfaz: sustituir
 * Redis Streams por RabbitMQ o Kafka es añadir otra implementación.
 * <p>
 * Semántica: entrega al menos una vez. Los handlers son idempotentes frente a mensajes repetidos.
 */
public interface AiJobQueue {

    void publish(AiJob job);
}

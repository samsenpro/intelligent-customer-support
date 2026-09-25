package com.supportmind.conversation;

/**
 * Canal por el que llega la conversación. Añadir un canal nuevo (Telegram, SMS...) es añadir un valor
 * aquí, en la restricción de la tabla y un {@code ChannelGateway} que entregue los mensajes salientes.
 */
public enum Channel {
    WEB,
    WHATSAPP,
    EMAIL,
    API
}

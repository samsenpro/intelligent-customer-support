package com.supportmind.conversation.channel;

import com.supportmind.conversation.Channel;
import com.supportmind.conversation.Conversation;
import com.supportmind.message.Message;

import java.util.Set;

/**
 * Entrega al cliente las respuestas (de un agente o de la IA) por el canal de la conversación.
 * Integrar un canal nuevo (WhatsApp Business API, un proveedor de email, Telegram...) es añadir una
 * implementación: el resto de la plataforma no cambia.
 */
public interface ChannelGateway {

    Set<Channel> channels();

    void deliver(Conversation conversation, Message message);
}

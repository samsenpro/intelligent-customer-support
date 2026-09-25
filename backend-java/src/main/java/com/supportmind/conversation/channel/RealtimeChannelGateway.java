package com.supportmind.conversation.channel;

import com.supportmind.conversation.Channel;
import com.supportmind.conversation.Conversation;
import com.supportmind.message.Message;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WEB y API: el cliente recibe los mensajes por la propia plataforma (SSE o consultando la API), así
 * que no hay nada más que entregar.
 */
@Component
public class RealtimeChannelGateway implements ChannelGateway {

    @Override
    public Set<Channel> channels() {
        return Set.of(Channel.WEB, Channel.API);
    }

    @Override
    public void deliver(Conversation conversation, Message message) {
        // Entregado por los eventos en tiempo real
    }
}

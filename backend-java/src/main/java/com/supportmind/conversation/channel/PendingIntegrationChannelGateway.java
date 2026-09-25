package com.supportmind.conversation.channel;

import com.supportmind.conversation.Channel;
import com.supportmind.conversation.Conversation;
import com.supportmind.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WHATSAPP y EMAIL: la conversación y sus mensajes se gestionan en la plataforma, pero el envío real
 * requiere credenciales de un proveedor externo que este proyecto no incluye. Este adaptador deja
 * constancia en el log; se sustituye por la integración real implementando {@link ChannelGateway}.
 */
@Component
public class PendingIntegrationChannelGateway implements ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(PendingIntegrationChannelGateway.class);

    @Override
    public Set<Channel> channels() {
        return Set.of(Channel.WHATSAPP, Channel.EMAIL);
    }

    @Override
    public void deliver(Conversation conversation, Message message) {
        log.info("Outbound {} message {} of conversation {} stored; no delivery provider configured",
                conversation.getChannel(), message.getId(), conversation.getId());
    }
}

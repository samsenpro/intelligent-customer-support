package com.supportmind.conversation.channel;

import com.supportmind.conversation.Channel;
import com.supportmind.conversation.Conversation;
import com.supportmind.message.Message;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Elige el gateway del canal de la conversación. */
@Component
public class ChannelRouter {

    private final Map<Channel, ChannelGateway> gateways = new EnumMap<>(Channel.class);

    public ChannelRouter(List<ChannelGateway> gateways) {
        for (ChannelGateway gateway : gateways) {
            gateway.channels().forEach(channel -> this.gateways.put(channel, gateway));
        }
        for (Channel channel : Channel.values()) {
            if (!this.gateways.containsKey(channel)) {
                throw new IllegalStateException("No ChannelGateway for channel " + channel);
            }
        }
    }

    public void deliver(Conversation conversation, Message message) {
        gateways.get(conversation.getChannel()).deliver(conversation, message);
    }
}

package com.example.claudeplayer.body;

import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * The body's network connection: it drops every packet. Forge's player login code needs a live channel, so
 * {@link AgentBody} wraps this in an EmbeddedChannel (which fires channelActive and sets the channel). Dropping
 * packets here also keeps the unsent queue from growing forever.
 */
final class NullConnection extends Connection {
    NullConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet) {}

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {}
}

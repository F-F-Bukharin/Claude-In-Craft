package com.example.claudeplayer.net;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.ClaudePlayerMod;
import com.example.claudeplayer.client.BrainScreen;
import com.example.claudeplayer.command.CpCommands;
import com.example.claudeplayer.core.brain.BrainPanel;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The Brain panel's channel. It is optional: a client without the mod still joins, and is never sent a panel. The
 * panel is pulled: an open panel asks for a fresh queue about once a second, and the server answers with the queue as
 * it is then.
 */
public final class BrainNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ClaudePlayerMod.MODID, "brain"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    private BrainNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, PanelPacket.class, PanelPacket::encode, PanelPacket::decode, PanelPacket::handle);
        CHANNEL.registerMessage(1, RefreshRequest.class, RefreshRequest::encode, RefreshRequest::decode,
                RefreshRequest::handle);
    }

    /** Whether this player may see the panel: the same players who may use /cp. */
    public static boolean mayOpen(ServerPlayer player) {
        return CpCommands.canControl(player.createCommandSourceStack());
    }

    /** Sends the panel to one player whose client has the mod. Server thread only. */
    public static void sendPanel(ServerPlayer player, AgentRuntime rt, boolean open) {
        if (!CHANNEL.isRemotePresent(player.connection.connection)) return;
        List<String> lines = BrainPanel.lines(rt.loop().orderFrom(), rt.loop().orderText(), rt.guardrails().halted(),
                rt.queue().snapshot());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PanelPacket(open, lines));
    }

    /** Client only: asks the server for a fresh queue. */
    public static void requestRefresh() {
        CHANNEL.sendToServer(new RefreshRequest());
    }

    /**
     * Server to client: the panel's lines. {@code open} asks the client to open the panel; without it the packet only
     * refreshes a panel that is already open.
     */
    public record PanelPacket(boolean open, List<String> lines) {
        void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(open);
            buf.writeCollection(lines, FriendlyByteBuf::writeUtf);
        }

        static PanelPacket decode(FriendlyByteBuf buf) {
            return new PanelPacket(buf.readBoolean(), buf.readList(FriendlyByteBuf::readUtf));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BrainScreen.receive(open, lines)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Client to server: an open panel asks for a fresh queue. Carries nothing. */
    public record RefreshRequest() {
        void encode(FriendlyByteBuf buf) {}

        static RefreshRequest decode(FriendlyByteBuf buf) {
            return new RefreshRequest();
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                AgentRuntime rt = AgentRuntime.get();
                if (sender != null && rt != null && mayOpen(sender)) sendPanel(sender, rt, false);
            });
            ctx.get().setPacketHandled(true);
        }
    }
}

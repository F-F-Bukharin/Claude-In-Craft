package com.example.claudeplayer.net;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.ClaudePlayerMod;
import com.example.claudeplayer.client.BrainScreen;
import com.example.claudeplayer.command.CpCommands;
import com.example.claudeplayer.core.brain.AgentLoop;
import com.example.claudeplayer.core.brain.BrainPanel;
import com.example.claudeplayer.core.safety.CautionLevel;
import java.util.ArrayList;
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
 * panel is pulled: an open panel asks for a fresh copy about once a second, and the server answers with the panel as
 * it is then.
 */
public final class BrainNetwork {
    private static final String PROTOCOL = "2"; // 2: titled sections instead of flat lines
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ClaudePlayerMod.MODID, "brain"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    /** Wire caps: the panel is small, and a bad packet must not make a client allocate much. */
    static final int MAX_SECTIONS = 8;
    static final int MAX_LINES = 40;
    static final int TITLE_MAX = 64;
    static final int LINE_MAX = 600; // longer than WorldText.ORDER_MAX plus its quotes

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
        AgentLoop loop = rt.loop();
        CautionLevel level = rt.level();
        BrainPanel.View view = new BrainPanel.View(loop.orderFrom(), loop.orderText(), loop.goalRunning(),
                rt.guardrails().halted(), loop.plan(), loop.lastReplyText(), loop.callInFlight(),
                rt.queue().snapshot(), loop.recentResults(), loop.steps(), loop.maxSteps(),
                level.number(), level.title, rt.settings().attacksEnabled(),
                rt.guardrails().callsMade(), rt.guardrails().tokensUsed());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PanelPacket(open, BrainPanel.sections(view)));
    }

    /** Client only: asks the server for a fresh panel. */
    public static void requestRefresh() {
        CHANNEL.sendToServer(new RefreshRequest());
    }

    /**
     * Server to client: the panel's sections. {@code open} asks the client to open the panel; without it the packet
     * only refreshes a panel that is already open. Counts and lengths are capped both ways, and a short packet decodes
     * to what it holds instead of failing.
     */
    public record PanelPacket(boolean open, List<BrainPanel.Section> sections) {
        void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(open);
            List<BrainPanel.Section> out = sections == null ? List.of() : sections;
            int count = Math.min(out.size(), MAX_SECTIONS);
            buf.writeVarInt(count);
            for (BrainPanel.Section s : out.subList(0, count)) {
                buf.writeUtf(clip(s.title(), TITLE_MAX), TITLE_MAX);
                List<String> lines = s.lines() == null ? List.of() : s.lines();
                int n = Math.min(lines.size(), MAX_LINES);
                buf.writeVarInt(n);
                for (String line : lines.subList(0, n)) buf.writeUtf(clip(line, LINE_MAX), LINE_MAX);
            }
        }

        static PanelPacket decode(FriendlyByteBuf buf) {
            boolean open = buf.isReadable() && buf.readBoolean();
            List<BrainPanel.Section> sections = new ArrayList<>();
            try {
                int count = buf.isReadable() ? Math.min(Math.max(buf.readVarInt(), 0), MAX_SECTIONS) : 0;
                for (int i = 0; i < count && buf.isReadable(); i++) {
                    String title = buf.readUtf(TITLE_MAX);
                    int n = buf.isReadable() ? Math.min(Math.max(buf.readVarInt(), 0), MAX_LINES) : 0;
                    List<String> lines = new ArrayList<>();
                    for (int j = 0; j < n && buf.isReadable(); j++) lines.add(buf.readUtf(LINE_MAX));
                    sections.add(new BrainPanel.Section(title, lines));
                }
            } catch (RuntimeException e) {
                // A cut-off or malformed packet: keep the sections read so far.
            }
            return new PanelPacket(open, List.copyOf(sections));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BrainScreen.receive(open, sections)));
            ctx.get().setPacketHandled(true);
        }

        /** Never null, and short enough for writeUtf's cap (which counts chars). */
        private static String clip(String s, int max) {
            if (s == null) return "";
            if (s.length() <= max) return s;
            int cut = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
            return s.substring(0, cut);
        }
    }

    /** Client to server: an open panel asks for a fresh copy. Carries nothing. */
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

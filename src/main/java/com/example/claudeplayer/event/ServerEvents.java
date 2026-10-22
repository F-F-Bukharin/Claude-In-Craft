package com.example.claudeplayer.event;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.ClaudePlayerMod;
import com.example.claudeplayer.command.CpCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge event wiring. Each handler forwards to the runtime while a server is running. */
@Mod.EventBusSubscriber(modid = ClaudePlayerMod.MODID)
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CpCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AgentRuntime.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AgentRuntime.stop();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        AgentRuntime rt = AgentRuntime.get();
        if (event.phase == TickEvent.Phase.END && rt != null) rt.tick();
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        AgentRuntime rt = AgentRuntime.get();
        if (rt != null) rt.onChat(event.getPlayer(), event.getRawText());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        AgentRuntime rt = AgentRuntime.get();
        if (rt != null && event.getEntity() instanceof ServerPlayer player) rt.onPlayerJoined(player);
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        AgentRuntime rt = AgentRuntime.get();
        if (rt != null) rt.damage().onHurt(event);
    }
}

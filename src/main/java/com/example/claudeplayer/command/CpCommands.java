package com.example.claudeplayer.command;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.Config;
import com.example.claudeplayer.core.chat.ChatIntake;
import com.example.claudeplayer.core.memory.Memory;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /cp. Admins (op level 2, or the single-player owner) can do everything. Allowlisted players can spawn, stop,
 * check status, read memory and test the key. Caution level and allowlist changes are admin-only here
 * (allowlisted players can also set the level from chat). Claude itself has no path to any of these.
 */
public final class CpCommands {
    private CpCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cp")
                .then(Commands.literal("spawn").requires(CpCommands::canControl)
                        .executes(c -> reply(c.getSource(), rt -> rt.body().spawnAt(c.getSource().getLevel(),
                                c.getSource().getPosition(), c.getSource().getRotation().y))))
                .then(Commands.literal("despawn").requires(CpCommands::canControl)
                        .executes(c -> reply(c.getSource(), rt -> rt.body().despawn())))
                .then(Commands.literal("respawn").requires(CpCommands::canControl)
                        .executes(c -> reply(c.getSource(), rt -> rt.body().respawn())))
                .then(Commands.literal("stop").requires(CpCommands::canControl)
                        .executes(c -> reply(c.getSource(), rt -> {
                            rt.halt();
                            return "Stopped. Model calls are blocked until the next order.";
                        })))
                .then(Commands.literal("status").requires(CpCommands::canControl)
                        .executes(c -> reply(c.getSource(), AgentRuntime::status)))
                .then(Commands.literal("memory").requires(CpCommands::canControl)
                        .executes(c -> reply(c.getSource(), CpCommands::memory)))
                .then(Commands.literal("keycheck").requires(CpCommands::canControl)
                        .executes(c -> keycheck(c.getSource())))
                .then(Commands.literal("level").requires(CpCommands::isAdmin)
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 5))
                                .executes(c -> reply(c.getSource(), rt -> rt.setLevel(
                                        CautionLevel.of(IntegerArgumentType.getInteger(c, "level")), c.getSource().getTextName())))))
                .then(Commands.literal("allow").requires(CpCommands::isAdmin)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(c -> allow(c.getSource(), StringArgumentType.getString(c, "player"), true))))
                .then(Commands.literal("deny").requires(CpCommands::isAdmin)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(c -> allow(c.getSource(), StringArgumentType.getString(c, "player"), false)))));
    }

    static boolean isAdmin(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        ServerPlayer p = source.getPlayer();
        return p != null && source.getServer().isSingleplayerOwner(p.getGameProfile());
    }

    static boolean canControl(CommandSourceStack source) {
        if (isAdmin(source)) return true;
        ServerPlayer p = source.getPlayer();
        return p != null && ChatIntake.allowed(p.getGameProfile().getName(), Config.allowlist());
    }

    private static int reply(CommandSourceStack source, Function<AgentRuntime, String> action) {
        AgentRuntime rt = AgentRuntime.get();
        if (rt == null) {
            source.sendFailure(Component.literal("Claude Player isn't running."));
            return 0;
        }
        String message = action.apply(rt);
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int keycheck(CommandSourceStack source) {
        return reply(source, rt -> {
            rt.keycheck(result -> source.sendSuccess(() -> Component.literal(result), false));
            return "Checking the API key...";
        });
    }

    private static String memory(AgentRuntime rt) {
        List<Memory.Entry> longTerm = rt.memory().longTerm();
        List<Memory.Entry> shortTerm = rt.memory().shortTerm();
        StringBuilder b = new StringBuilder("Long-term (" + longTerm.size() + "):");
        for (Memory.Entry e : longTerm) b.append("\n  ").append(e.subject()).append(": ").append(e.text());
        b.append("\nShort-term (").append(shortTerm.size()).append("):");
        for (Memory.Entry e : shortTerm) b.append("\n  ").append(e.subject()).append(": ").append(e.text());
        return b.toString();
    }

    private static int allow(CommandSourceStack source, String name, boolean add) {
        List<String> names = new ArrayList<>(Config.allowlist());
        names.removeIf(n -> n.equalsIgnoreCase(name));
        if (add) names.add(name);
        Config.setAllowlist(names);
        String message = (add ? "Allowed " : "Removed ") + name + ". Allowlist: " + (names.isEmpty() ? "(empty)" : String.join(", ", names));
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }
}

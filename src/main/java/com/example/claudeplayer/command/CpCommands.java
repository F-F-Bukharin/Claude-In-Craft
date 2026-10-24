package com.example.claudeplayer.command;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.Config;
import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.chat.ChatIntake;
import com.example.claudeplayer.core.memory.Memory;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /cp. Anyone can read /cp help. Admins (op level 2, or the single-player owner) can do everything. Allowlisted
 * players can do everything except change the caution level or the allowlist (they can set the level from chat).
 * Claude itself has no path to any of these.
 */
public final class CpCommands {
    private CpCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cp")
                .executes(c -> help(c.getSource()))
                .then(Commands.literal("help").executes(c -> help(c.getSource())))
                .then(Commands.literal("quickstart").requires(CpCommands::canControl)
                        .executes(c -> quickstart(c.getSource())))
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
                .then(Commands.literal("resetcap").requires(CpCommands::isAdmin)
                        .executes(c -> reply(c.getSource(), rt -> {
                            rt.resetTokenCap();
                            return "Token cap cleared.";
                        })))
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

    public static boolean isAdmin(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        ServerPlayer p = source.getPlayer();
        return p != null && source.getServer().isSingleplayerOwner(p.getGameProfile());
    }

    public static boolean canControl(CommandSourceStack source) {
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

    private static int quickstart(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return reply(source, rt -> rt.quickstart(player, isAdmin(source)));
    }

    private static int keycheck(CommandSourceStack source) {
        return reply(source, rt -> {
            rt.keycheck(result -> source.sendSuccess(() -> Component.literal(result), false));
            return rt.settings().usesApi() ? "Checking the API key..." : "Checking Claude Code...";
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
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(name);
        if (target != null) source.getServer().getCommands().sendCommands(target); // update the /cp commands they see
        String message = (add ? "Allowed " : "Removed ") + name + ". Allowlist: " + (names.isEmpty() ? "(empty)" : String.join(", ", names));
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    /** /cp and /cp help. Every command line can be clicked to drop it into the chat box. */
    private static int help(CommandSourceStack source) {
        AgentRuntime rt = AgentRuntime.get();
        Settings s = rt != null ? rt.settings() : Settings.DEFAULTS;
        String t = s.trigger();
        String name = s.bodyName();
        MutableComponent help = Component.literal("Claude Player").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("  (click a line to put it in chat)").withStyle(ChatFormatting.DARK_GRAY));
        section(help, "Start here");
        entry(help, "/cp quickstart", "/cp quickstart", "allow you, bring " + name + " here, run the demo");
        section(help, "Talk to " + name + " (allowlisted players)");
        entry(help, t + " come here", t + " ", "any order, in plain words");
        entry(help, t + " stop", t + " stop", "halt everything now");
        entry(help, t + " go do your thing", t + " go do your thing", "go solo: mine, craft, smelt, survive; orders still work");
        entry(help, t + " auto off", t + " auto off", "end autonomy without the kill switch");
        entry(help, t + " respawn", t + " respawn", "bring " + name + " back after a death");
        entry(help, t + " level <0-5>", t + " level ", "caution: 0 guns blazing ... 3 reasonable ... 5 innocent");
        entry(help, t + " demo", t + " demo", "walk a small square, no API key needed");
        section(help, "Commands");
        entry(help, "/cp spawn", "/cp spawn", "spawn " + name + " here; this spot becomes home");
        entry(help, "/cp despawn", "/cp despawn", "remove " + name + " from the world");
        entry(help, "/cp respawn", "/cp respawn", "back at home after a death");
        entry(help, "/cp stop", "/cp stop", "kill switch: cancel and block model calls");
        entry(help, "/cp status", "/cp status", "body, caution level, queue, calls, weighted tokens");
        entry(help, "/cp resetcap", "/cp resetcap", "clear the hourly token cap now (ops)");
        entry(help, "/cp memory", "/cp memory", "what " + name + " remembers");
        entry(help, "/cp keycheck", "/cp keycheck", s.usesApi() ? "test the API key (free)" : "test the Claude Code connection");
        entry(help, "/cp level <0-5>", "/cp level ", "set the caution level (ops)");
        entry(help, "/cp allow <player>", "/cp allow ", "let a player give orders; /cp deny takes it back (ops)");
        help.append(Component.literal(s.usesApi()
                        ? "\nAPI key: set " + s.apiKeyEnv() + ", or put it in config/claudeplayer.key."
                        : "\nBrain: your Claude Code login (subscription). brain = \"api\" in the config switches to an API key.")
                .withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> help, false);
        return 1;
    }

    private static void section(MutableComponent help, String title) {
        help.append(Component.literal("\n" + title).withStyle(ChatFormatting.YELLOW));
    }

    private static void entry(MutableComponent help, String shown, String suggestion, String description) {
        help.append(Component.literal("\n  " + shown).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestion))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to put it in chat")))))
                .append(Component.literal("  " + description).withStyle(ChatFormatting.GRAY));
    }
}

package com.example.claudeplayer;

import com.example.claudeplayer.body.ActionExecutor;
import com.example.claudeplayer.body.AgentBody;
import com.example.claudeplayer.body.AgentData;
import com.example.claudeplayer.body.AgentPlayer;
import com.example.claudeplayer.body.SnapshotBuilder;
import com.example.claudeplayer.command.CpCommands;
import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.StateSnapshot;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.brain.AgentLoop;
import com.example.claudeplayer.core.brain.ApiBrain;
import com.example.claudeplayer.core.brain.Brain;
import com.example.claudeplayer.core.brain.BrainException;
import com.example.claudeplayer.core.brain.CavemanSkill;
import com.example.claudeplayer.core.brain.ClaudeCodeBrain;
import com.example.claudeplayer.core.brain.DemoBrain;
import com.example.claudeplayer.core.brain.JsonUsageFile;
import com.example.claudeplayer.core.brain.PromptBuilder;
import com.example.claudeplayer.core.brain.ToolSchemas;
import com.example.claudeplayer.core.brain.UsageLedger;
import com.example.claudeplayer.core.chat.ChatIntake;
import com.example.claudeplayer.core.chat.ChatLines;
import com.example.claudeplayer.core.chat.Conversation;
import com.example.claudeplayer.core.chat.ThinkHarder;
import com.example.claudeplayer.core.memory.JsonMemoryFile;
import com.example.claudeplayer.core.memory.Memory;
import com.example.claudeplayer.core.memory.MemoryActions;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.Guardrails;
import com.example.claudeplayer.core.safety.Reflexes;
import com.example.claudeplayer.core.safety.WorldText;
import com.example.claudeplayer.event.DamageWatcher;
import com.example.claudeplayer.event.HungerWatcher;
import com.example.claudeplayer.nav.EscapePlanner;
import com.example.claudeplayer.nav.Navigator;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Everything that lives for one running server: settings, guardrails, memory, body, queue, brain and loop.
 * Created on ServerStartedEvent and torn down on ServerStoppingEvent. Server thread only, apart from the brain
 * executor, whose results come back through {@link #onMainThread}.
 */
public final class AgentRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    /** Set by {@code ./gradlew runClient -Pquickstart}: run /cp quickstart for the first player who joins. */
    private static final String QUICKSTART_PROPERTY = "claudeplayer.quickstart";
    private static final int QUICKSTART_DELAY_TICKS = 60; // let the client finish loading the world first
    private static AgentRuntime current;

    private final MinecraftServer server;
    private final Settings settings;
    private final Guardrails guardrails;
    private final Conversation conversation = new Conversation(10);
    private final Memory memory;
    private final AgentData data;
    private final AgentBody body;
    private final Navigator navigator = new Navigator();
    private final EscapePlanner escape;
    private final ActionQueue queue;
    private final DamageWatcher damage;
    private final HungerWatcher hunger;
    private final ExecutorService brainThread;
    private final ApiBrain brain;
    private final ClaudeCodeBrain claudeCode;
    private final UsageLedger usage;
    private final Optional<CavemanSkill> caveman;
    private final ThinkHarder thinkHarder;
    private String offeredTo; // who the open think-harder offer went to; its order is held in thinkHarder
    private final AgentLoop loop;
    private final Path keyFile;
    private volatile boolean closed;
    private long ticks;
    private ServerPlayer quickstartFor;
    private long quickstartAt;
    private boolean quickstartRan;

    private AgentRuntime(MinecraftServer server) {
        this.server = server;
        this.settings = Config.settings();
        this.guardrails = new Guardrails(settings, System::currentTimeMillis);
        this.thinkHarder = new ThinkHarder(settings.thinkHarderPhrases(), settings.thinkHarderTimeoutSeconds(),
                System::currentTimeMillis);
        this.memory = new Memory(settings,
                new JsonMemoryFile(server.getWorldPath(LevelResource.ROOT).resolve(ClaudePlayerMod.MODID).resolve("memory.json")),
                System::currentTimeMillis);
        this.data = AgentData.get(server);
        this.body = new AgentBody(this);
        this.escape = new EscapePlanner(navigator, settings);
        this.queue = new ActionQueue(settings.actionsPerSecond(), settings.actionTimeoutSeconds() * 20,
                new MemoryActions(memory, new ActionExecutor(this)));
        this.damage = new DamageWatcher(this);
        this.hunger = new HungerWatcher(this);
        this.brainThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claudeplayer-brain");
            t.setDaemon(true);
            return t;
        });
        Path configDir = FMLPaths.CONFIGDIR.get();
        this.keyFile = configDir.resolve("claudeplayer.key");
        this.brain = new ApiBrain(settings, () -> ApiBrain.resolveKey(settings.apiKeyEnv(), keyFile));
        this.usage = new UsageLedger(settings.usageWarnAt(), new JsonUsageFile(configDir.resolve("claudeplayer_usage.json")),
                System::currentTimeMillis);
        // Under the system temp dir, outside any project, so Claude Code loads no CLAUDE.md.
        this.claudeCode = new ClaudeCodeBrain(settings, Path.of(System.getProperty("java.io.tmpdir"), "claudeplayer-claude-code"), usage);
        String home = System.getProperty("user.home");
        this.caveman = CavemanSkill.load(expandHome(settings.cavemanSkillPath(), home), Path.of(home, ".claude", "skills"));
        if (caveman.isEmpty()) LOG.info("no caveman skill found; caveman stays off");
        caveman.ifPresent(claudeCode::caveman);
        PromptBuilder prompts = new PromptBuilder(settings,
                PromptBuilder.loadTemplate(configDir.resolve("claudeplayer_system_prompt.md")));
        Brain backend = settings.usesApi() ? brain : claudeCode;
        ToolSchemas tools = new ToolSchemas(settings.toolPhase());
        this.loop = new AgentLoop(settings, new DemoBrain(backend), brainThread, this::onMainThread, queue, guardrails, prompts,
                tools, memory, new LoopHost(), System::currentTimeMillis);
        if (!settings.usesApi()) loop.caveman(this::cavemanOn, () -> caveman.map(CavemanSkill::note).orElse(""));
        if (!settings.usesApi()) brainThread.execute(() -> claudeCode.prewarm(prompts.system(), tools.definitions()));
        LOG.info("Claude Player ready: brain {}, model {}, caution level {}, {} allowlisted, {} long-term memories",
                settings.brain(), settings.model(), level().number(), Config.allowlist().size(), memory.longTerm().size());
    }

    public static @Nullable AgentRuntime get() {
        return current;
    }

    public static void start(MinecraftServer server) {
        stop();
        current = new AgentRuntime(server);
        current.body.restore();
        // Whoever is already online missed PlayerLoggedInEvent (single-player can log in before this runs).
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) current.onPlayerJoined(player);
    }

    public static void stop() {
        if (current == null) return;
        current.shutdown();
        current = null;
    }

    public void tick() {
        ticks++;
        body.tick();
        damage.tick(); // reflexes enqueue before the queue ticks, so a level-3 action starts this tick
        hunger.tick();
        queue.tick();
        loop.tick();
        for (String warning : usage.drainWarnings()) noticeAllowlisted(warning);
        String unanswered = thinkHarder.expired(); // no answer in time counts as no
        if (unanswered != null) order(offeredTo, unanswered, false);
        if (quickstartFor != null && ticks >= quickstartAt) {
            ServerPlayer player = quickstartFor;
            quickstartFor = null;
            if (!player.hasDisconnected()) {
                String result = quickstart(player, CpCommands.isAdmin(player.createCommandSourceStack()));
                player.sendSystemMessage(Component.literal(result).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    public void onChat(ServerPlayer player, String raw) {
        if (body.is(player)) return;
        String name = player.getGameProfile().getName();
        List<String> allowlist = Config.allowlist();
        if (ChatIntake.allowed(name, allowlist)) conversation.add(name, raw);
        ChatIntake.Intake intake = ChatIntake.classify(name, raw, settings.triggers(), allowlist);
        if (intake instanceof ChatIntake.Order order) {
            chatOrder(order.player(), order.text());
        } else if (intake instanceof ChatIntake.Stop) {
            halt();
        } else if (intake instanceof ChatIntake.Respawn) {
            notice(body.respawn());
        } else if (intake instanceof ChatIntake.SetLevel set) {
            setLevel(set.level(), set.player());
        } else if (intake instanceof ChatIntake.Autonomy auto) {
            autonomy(auto.player(), auto.on());
        }
    }

    /** Kill switch, from {@code C stop} or {@code /cp stop}. Ends autonomy too. */
    public void halt() {
        thinkHarder.takeOrder(); // a held think-harder order must not start after a stop
        boolean wasAutonomous = loop.autonomous();
        loop.stop();
        navigator.stop(body.player());
        if (body.alive()) say(wasAutonomous ? "Stopping. Autonomy off." : "Stopping.");
    }

    public String setLevel(CautionLevel level, String by) {
        data.setLevel(level.number());
        String msg = "Caution level " + level.number() + " (" + level.title + "), set by " + by + ".";
        notice(msg);
        return msg;
    }

    public CautionLevel level() {
        return CautionLevel.of(data.level() < 0 ? settings.defaultLevel() : data.level());
    }

    /** Chat as the body. */
    public void say(String text) {
        List<String> lines = ChatLines.fit(WorldText.clean(text, 256));
        if (lines.isEmpty()) return;
        conversation.add(settings.bodyName(), String.join(" ", lines));
        for (String line : lines) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("<" + settings.bodyName() + "> " + line), false);
        }
    }

    /** A gray system line in chat. */
    public void notice(String text) {
        if (text == null || text.isEmpty()) return;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("[Claude Player] " + text).withStyle(ChatFormatting.GRAY), false);
    }

    /** A gray system line for the online players who can give orders; nobody else needs to hear about usage. */
    private void noticeAllowlisted(String text) {
        List<String> allowlist = Config.allowlist();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof AgentPlayer || !ChatIntake.allowed(player.getGameProfile().getName(), allowlist)) continue;
            noticePlayer(player, text);
        }
    }

    /** A gray system line for one player. */
    private static void noticePlayer(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[Claude Player] " + text).withStyle(ChatFormatting.GRAY));
    }

    /** The body died: the loop stops and waits for a respawn order (death is accepted, D3). */
    public void onBodyDied(String cause) {
        boolean wasAutonomous = loop.autonomous();
        loop.bodyGone();
        say("I died (" + cause + "). Respawn me with " + settings.trigger() + " respawn or /cp respawn."
                + (wasAutonomous ? " Autonomy off." : ""));
    }

    /** /cp keycheck, on the brain thread: a free count_tokens call (api), or one tiny Claude Code call. */
    public void keycheck(Consumer<String> reply) {
        brainThread.execute(() -> {
            String msg;
            try {
                if (settings.usesApi()) {
                    brain.checkKey();
                    msg = "The API key works with " + settings.model() + ".";
                } else {
                    msg = claudeCode.check();
                }
            } catch (BrainException e) {
                msg = e.chatMessage(settings.apiKeyEnv());
            }
            String result = msg;
            onMainThread(() -> reply.accept(result));
        });
    }

    /**
     * /cp quickstart, in one go: allow the caller, make sure the body is next to them (spawn, or fetch it if it's
     * far away), and start the demo. Returns one line per step.
     */
    public String quickstart(ServerPlayer player, boolean admin) {
        String name = player.getGameProfile().getName();
        List<String> steps = new ArrayList<>();
        if (ChatIntake.allowed(name, Config.allowlist())) {
            steps.add("1. " + name + " can already give orders.");
        } else if (admin) {
            List<String> allowlist = new ArrayList<>(Config.allowlist());
            allowlist.add(name);
            Config.setAllowlist(allowlist);
            server.getCommands().sendCommands(player); // refresh which /cp commands their client offers
            steps.add("1. Allowed " + name + " to give orders.");
        } else {
            return "Only an op can add you to the allowlist.";
        }
        AgentPlayer existing = body.player();
        if (existing == null) {
            steps.add("2. " + body.spawnAt(player.serverLevel(), player.position(), player.getYRot()));
        } else if (existing.level() != player.level() || existing.distanceTo(player) > 16) {
            existing.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
            steps.add("2. Brought " + settings.bodyName() + " to you. Home is unchanged.");
        } else {
            steps.add("2. " + settings.bodyName() + " is already here.");
        }
        if (!body.alive()) return String.join("\n", steps); // spawning failed; its message says why
        loop.startOrder(name, "demo");
        steps.add("3. Demo running: " + settings.bodyName() + " walks a 3-block square. \""
                + settings.trigger() + " stop\" halts it.");
        steps.add(keyStatus());
        steps.add("Next, try \"" + settings.trigger() + " come here\". /cp help lists everything.");
        return String.join("\n", steps);
    }

    /** Whether the brain is set up, without calling it (/cp keycheck makes the real test). */
    public String keyStatus() {
        if (!settings.usesApi()) {
            String exe = claudeCode.executable();
            return exe != null
                    ? "Brain: your Claude Code login (" + exe + "); /cp keycheck tests it."
                    : "Claude Code wasn't found. Install it and log in once, or set claude_code_path in the config.";
        }
        return ApiBrain.resolveKey(settings.apiKeyEnv(), keyFile) != null
                ? "API key found; /cp keycheck tests it."
                : "No API key yet. For real orders set " + settings.apiKeyEnv()
                + " or put the key in config/claudeplayer.key, then run /cp keycheck.";
    }

    /** A real player joined: point them at /cp help, and run the quickstart if the dev launch asked for it. */
    public void onPlayerJoined(ServerPlayer player) {
        if (player instanceof AgentPlayer || !CpCommands.canControl(player.createCommandSourceStack())) return;
        player.sendSystemMessage(Component.literal("[Claude Player] /cp help shows how to use " + settings.bodyName() + ".")
                .withStyle(ChatFormatting.GRAY));
        if (Boolean.getBoolean(QUICKSTART_PROPERTY) && !quickstartRan) {
            quickstartRan = true;
            quickstartFor = player;
            quickstartAt = ticks + QUICKSTART_DELAY_TICKS;
        }
    }

    public String status() {
        String state = body.alive() ? "in the world" : body.dead() ? "dead" : "not spawned";
        CautionLevel l = level();
        String line = String.format(Locale.ROOT, "%s is %s | caution %d (%s) | %s | %s | queue: %s, %d waiting | memory: %d short, %d long | %d calls, %d tokens this hour",
                settings.bodyName(), state, l.number(), l.title, loop.status(), loop.autonomyStatus(),
                Objects.requireNonNullElse(queue.runningTool(), "idle"), queue.waitingCount(),
                memory.shortTerm().size(), memory.longTerm().size(), guardrails.callsMade(), guardrails.tokensUsed());
        StringBuilder b = new StringBuilder(line);
        if (!settings.usesApi()) {
            for (String usageLine : usage.statusLines()) b.append("\nClaude Code ").append(usageLine);
            b.append("\nCaveman: ").append(cavemanOn() ? "on" : "off")
                    .append(caveman.map(c -> ", sha256 " + c.sha256().substring(0, 12)).orElse(" (no skill found)"));
        }
        return b.toString();
    }

    public MinecraftServer server() {
        return server;
    }

    public Settings settings() {
        return settings;
    }

    /** /cp resetcap: clears the hour's token count so model calls go on. */
    public void resetTokenCap() {
        guardrails.resetTokens();
    }

    public Guardrails guardrails() {
        return guardrails;
    }

    public Memory memory() {
        return memory;
    }

    public AgentData data() {
        return data;
    }

    public AgentBody body() {
        return body;
    }

    public Navigator navigator() {
        return navigator;
    }

    public EscapePlanner escape() {
        return escape;
    }

    public ActionQueue queue() {
        return queue;
    }

    public DamageWatcher damage() {
        return damage;
    }

    public AgentLoop loop() {
        return loop;
    }

    public long ticks() {
        return ticks;
    }

    /** Caveman applies to an order when the skill exists and either usage window has reached caveman_at. */
    private boolean cavemanOn() {
        return caveman.isPresent() && usage.utilization() >= settings.cavemanAt();
    }

    /** A leading {@code ~} in a configured path means the user's home folder. */
    private static String expandHome(String path, String home) {
        if (path == null) return "";
        String p = path.strip();
        if (p.equals("~")) return home;
        return p.startsWith("~/") || p.startsWith("~\\") ? Path.of(home, p.substring(2)).toString() : p;
    }

    /**
     * An order from chat. A y or n from the player who was offered think-harder answers the offer. An order with a
     * think-harder phrase runs at normal effort if a hostile is near, else is held while the player is asked. Any
     * other order replaces a held one.
     */
    private void chatOrder(String player, String text) {
        ThinkHarder.Answer answer = thinkHarder.answer(player, text);
        if (answer != ThinkHarder.Answer.NONE) {
            String held = thinkHarder.takeOrder();
            if (held != null) order(player, held, answer == ThinkHarder.Answer.YES);
            return;
        }
        thinkHarder.takeOrder();
        if (settings.usesApi() || !thinkHarder.wants(text)) { // the API brain has no max effort to offer
            order(player, text, false);
            return;
        }
        if (bodyMissing()) return;
        AgentPlayer p = body.player();
        List<Mob> hostiles = SnapshotBuilder.hostilesXZ(p, settings.hostileRange());
        if (!hostiles.isEmpty()) {
            Mob nearest = hostiles.get(0);
            say(ThinkHarder.gate(SnapshotBuilder.typeId(nearest),
                    Reflexes.horizontal(nearest.getX() - p.getX(), nearest.getZ() - p.getZ())));
            order(player, text, false);
            return;
        }
        offeredTo = player;
        String offer = thinkHarder.offer(player, text, settings.trigger());
        ServerPlayer target = server.getPlayerList().getPlayerByName(player);
        if (target != null) noticePlayer(target, offer);
    }

    private void order(String player, String text, boolean think) {
        if (bodyMissing()) return;
        loop.startOrder(player, text, think);
    }

    /** {@code C go do your thing} / {@code C auto off}. Off leaves the kill switch alone and a running order too. */
    private void autonomy(String player, boolean on) {
        if (!on) {
            loop.stopAutonomy();
            say("Autonomy off.");
            return;
        }
        if (bodyMissing()) return;
        loop.startAutonomy(player);
        say("Going solo. Call me with " + settings.trigger() + " <order>.");
    }

    private boolean bodyMissing() {
        if (body.alive()) return false;
        notice(body.dead()
                ? settings.bodyName() + " is dead. Respawn with " + settings.trigger() + " respawn or /cp respawn."
                : settings.bodyName() + " isn't spawned. Use /cp spawn.");
        return true;
    }

    // server.tell always queues (execute would run inline on the server thread), and closed drops late results.
    private void onMainThread(Runnable task) {
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (!closed) task.run();
        }));
    }

    private void shutdown() {
        closed = true;
        loop.stop();
        body.despawnForShutdown();
        brainThread.shutdownNow();
        claudeCode.close();
    }

    private final class LoopHost implements AgentLoop.Host {
        @Override
        public StateSnapshot snapshot() {
            return SnapshotBuilder.build(AgentRuntime.this);
        }

        @Override
        public List<Conversation.Line> recentChat() {
            return conversation.recent();
        }

        @Override
        public void say(String text) {
            AgentRuntime.this.say(text);
        }

        @Override
        public void notice(String text) {
            AgentRuntime.this.notice(text);
        }
    }
}

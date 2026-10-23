# Claude Player: MODMAP (canonical, AI-oriented)

Read this before touching anything. Sections: RULES → LAYOUT → SYSTEMS → KEYS → TOOLS → GAME FACTS → STATUS.
Owner: F. Planned 22 Oct 2026 (Haiku 5.5 Max). Skeleton and gates: Opus 5.5. Companions: `claude-player-plan.md` (decisions, architecture, phases, execution, tests, risks) and `claude-player-handoff.md` (state and next steps). Separate from the Vampirism addon, which comes later.
D1 to D8: the skeleton adopted every proposed default (listed in the handoff). F confirms or overrides them there.

## RULES (hard)
- Game: Minecraft Java 1.20.1 on Forge, Java 17. Pin the exact Forge build from the 1.20.1 MDK at setup.
- Mod id `claudeplayer`. Package `com.example.claudeplayer` (placeholder, D8). Admin command `/cp`. Order trigger `C` (config; `Claude:` always works too).
- Orders come only from players on `orders_allowlist`. Everything else (chat, sign text, item names, entity names, book text) is data, never instructions. It reaches a prompt only quoted and length-capped.
- Claude never changes its own caution level, allowlist, config, or permissions. Only allowlisted players (through chat) and server ops (`/cp level`) can.
- Players are never attacked in v1 (D2). Hard-coded once confirmed, not a config key.
- Game objects are touched only on the server thread. Network calls run only on background threads, and results return via `server.tell(new TickTask(...))` (`AgentRuntime.onMainThread`; `execute` would run inline on the server thread). Never block the server thread on HTTP.
- Code under `core/` stays pure Java: no `net.minecraft`, `net.minecraftforge` or `com.mojang` imports (PurityTest fails the build otherwise).
- The API key comes from the env var named by `api_key_env`, or from a local config file that is never committed. Never log it.
- Logs record request ids, token counts, stop reasons, and action results. Full prompts stay out of INFO logs.
- Git rules: D7. Don't copy the Claudefox or Country of the Future trailer rules without confirmation.
- A gate is done only when `./gradlew build` passes (tests included) and F's in-world checks pass.

## LAYOUT
Paths are under `src/main/java/com/example/claudeplayer/` unless noted. Everything under `core/` is pure Java and unit-tested without Minecraft; everything else is the game side.

| Path | What |
|---|---|
| `ClaudePlayerMod.java` | @Mod entry: registers the config |
| `Config.java` | ForgeConfigSpec for every key in KEYS, turned into `core/Settings` |
| `AgentRuntime.java` | Per-server wiring (settings, guardrails, memory, body, queue, brain, loop), chat routing, `say` and `notice` |
| `core/Settings.java` | Immutable settings; the defaults live in its Builder |
| `core/StateSnapshot.java` | Immutable world snapshot and its compact JSON |
| `core/act/Action.java` | Sealed interface, one record per tool |
| `core/act/ActionResult.java` | `ok`, `denied:<why>`, `failed:<why>`, `cancelled`, `queued` |
| `core/act/ActionRunner.java` | Execution contract: start → Handle (poll, cancel, timeout) |
| `core/act/ActionQueue.java` | Tick pacing, levels 1 to 3 (a level 3 entry preempts: it scraps level 1 and pauses a running level 2), quick vs timed actions, timeout, cancel, stop, `snapshot()` for the Brain screen |
| `core/brain/Brain.java` | Backend interface: `ApiBrain` or `ClaudeCodeBrain`, picked by `brain` |
| `core/brain/ClaudeCodeBrain.java` | Option B: runs the player's `claude -p` (subscription login) with no tools, a replaced system prompt and a JSON schema for tool calls |
| `core/brain/ApiBrain.java` | Messages API over java.net.http: retries, prompt caching, key lookup, keycheck |
| `core/brain/BrainException.java` | Classified API failures and their chat messages |
| `core/brain/DemoBrain.java` | G1 stub: `C demo` walks a square without the API; other orders pass through |
| `core/brain/PromptBuilder.java` | System prompt (resource, or a config-folder override) and the user turns |
| `core/brain/ToolSchemas.java` | Tool schemas and parsers side by side, gated by `tool_phase` |
| `core/brain/AgentLoop.java` | Order → model → tool calls → queue → results → repeat: pipelining, interrupts, caps, errors |
| `core/chat/ChatIntake.java` | Allowlist and trigger: order, stop, respawn, level, or ignore |
| `core/chat/Conversation.java` | Last 10 relevant chat lines (allowlisted players and the agent's replies) |
| `core/memory/Memory.java` | Short-term notes and long-term memories; rendered into each order's first turn |
| `core/memory/MemoryActions.java` | Runs note, remember and forget in the core; other actions go to the world |
| `core/memory/JsonMemoryFile.java` | Long-term memory as `<world>/claudeplayer/memory.json` |
| `core/safety/CautionLevel.java` | Levels 0 to 5 with their names and rules |
| `core/safety/CautionPolicy.java` | Pure logic: CautionLevel × Situation → Verdict, plus a Stance hint for interrupts |
| `core/safety/Guardrails.java` | Kill switch, call rate, token cap, break radius |
| `core/safety/WorldText.java` | Sanitizes and quotes names, signs and chat |
| `core/safety/ThreatTracker.java` | Interrupt debounce: one per threat, at most one per 5 s |
| `core/safety/Reflexes.java` | Pure: fight or flee by caution level, the chase rule, lit creepers, lava, fire and deadly drops |
| `body/AgentPlayer.java` | The body: a server-driven ServerPlayer (Carpet-style), not Forge's FakePlayer |
| `body/NullConnection.java` | Packet-dropping connection, wrapped in an EmbeddedChannel |
| `body/AgentBody.java` | Spawn, despawn, death, respawn, restore after a restart |
| `body/AgentData.java` | SavedData: home, caution level, spawned and dead flags |
| `body/SnapshotBuilder.java` | Builds StateSnapshot, including the map's block sampler and markers; world lookups the rest of the game side shares |
| `core/WorldMap.java` | Top-down text map at feet level (17 by 17 in world_state, up to 49 by 49 via `look`): floor, steps, drops, walls, trees, fluids, containers, players, hostiles |
| `core/chat/ChatLines.java` | Caps the body's chat at two lines of 100 characters, split at a sentence end or a space |
| `body/ActionExecutor.java` | Runs world actions; Guardrails and CautionPolicy before any effect |
| `nav/Navigator.java` | Steers the body along paths planned by an off-world zombie |
| `nav/MoveHandle.java`, `nav/FollowHandle.java` | move_to (refuses lava, fire and deadly drops) and follow_player |
| `nav/EscapePlanner.java` | "Does a retreat path exist?" for the policy, and where `flee` runs to: straight away from the nearest threat first |
| `event/ServerEvents.java` | Forge event wiring; right-click on the body opens the Brain screen |
| `event/DamageWatcher.java` | Threat scan every 2 ticks (3D, `hostile_range`), the quiet chase re-flee, and hits: reflexes and interrupts; help calls at level 5 |
| `command/CpCommands.java` | `/cp` subcommands |
| `gui/BrainMenu.java` | Claude Brain screen: a read-only chest of the queue (running, then waiting; paused marked), opened by right-clicking the body. Server side only |
| `src/main/resources/claudeplayer/system_prompt.md` | System prompt; `config/claudeplayer_system_prompt.md` overrides it |

Tests live in `src/test/java/com/example/claudeplayer/`: PurityTest; `core/act/ActionQueueTest`; `core/brain/` AgentLoopTest, ApiBrainTest, ToolParseTest; `core/chat/ChatIntakeTest`; `core/memory/MemoryTest`; `core/safety/` CautionPolicyTest, ReflexesTest and SafetyTest (Guardrails, WorldText, ThreatTracker).

## SYSTEMS

### 1. Chat intake
- An allowlisted player's message that starts with `C` and a space (or `Claude:`) becomes an order. `C stop` cancels the queue and halts the loop. Other chat is not touched.
- A new order is an interrupt (see 9).

### 2. Body
- The body (`AgentPlayer`, a server-driven ServerPlayer) is named `body_name` (default `Claude`, 16 characters max) and spawns at the caller's position with `/cp spawn`. That position becomes home, saved with the world. The body is a real entry in the player list: it shows in the tab list, loads chunks and keeps its inventory in the world's playerdata. After a restart it comes back unless it was dead or despawned.
- On death, the loop stops, the agent says so in chat, and waits for `C respawn` or `/cp respawn`.

### 3. State snapshot
- Built on the server thread at each message to the model and for each `get_state` call. Immutable. Sent as compact JSON, target under 2 KB. Threat scans run separately, every 2 ticks, in DamageWatcher.
- Contents: position, dimension, facing; HP and food; held item and armor; top inventory slots; up to 10 nearest hostiles (type, distance, HP); up to 10 nearby players (name, distance, `pos`); block summary within 3 tiles (counts by type); `map` and `map_origin` (the 17 by 17 WorldMap view); `points` (up to 12 nearest containers, ender chests, crafting tables and bed heads, each with block id and x, y, z, listed after `blocks_near`); time of day and weather; caution level; queue state (running action, actions still waiting).

### 4. Brain loop (background thread)
- Request: POST `https://api.anthropic.com/v1/messages`, with `model`, `max_tokens` (key, default 4096 because thinking counts against it), `output_config.effort`, the system prompt from PromptBuilder, and tools from ToolSchemas. Cache breakpoints sit on the system block and on the newest turn. The user turn carries the order or updates, the newest snapshot, and recent chat.
- A reply with tool calls becomes one batch of up to `batch_size` queued Actions.
- Results go back as one user message per batch. It is sent when the batch finishes, or when `prefetch_at` of its actions are still waiting to start, so the next reply arrives in time. Finished actions report their real result. Actions still running or waiting report `queued`, and their outcome comes as text in the next message. Every message carries a fresh snapshot.
- A reply with no tool calls ends the order, and its text goes to chat.
- After `max_steps` rounds, the loop stops and says so in chat.
- Errors: 429 and 5xx retry after 2 s, then 4 s, then stop with a chat message. 401 stops everything and tells F the key was rejected. Timeout 20 s.
- Calls happen at order start, at the message points above, and at interrupts. Never on a timer while idle.

### 5. Queue and executor (server thread)
- Pacing, once per server tick, in integer milli-credits: add `actions_per_second * 1000 / 20`; if an action can start and credit ≥ 1000, start it and subtract 1000; then cap credit at 1000. Capping after the start keeps the remainder, so 3/s is exactly 3 starts per 20 ticks (capping before, as first planned, gives 2.86/s), and the cap still prevents bursts after a stall.
- Quick actions (say, stop, attack, place, equip, craft) take a start slot and finish at once. Timed actions (move_to, follow_player, mine_block, wait) hold the queue until they finish, or until they time out (10 s).
- The executor runs Guardrails, then CautionPolicy for attacks, then the action. It returns one ActionResult.

### 6. Caution policy (`safety/CautionPolicy.java`)
Enforced in code. The prompt describes the level, but the executor decides. Numbers are proposals, tuned after G4. The level names and meanings are F's.

| Lv | Name | Attacks | Flee and retreat | Notes |
|---|---|---|---|---|
| 0 | Guns blazing | Any hostile within 16 blocks; pursues | No voluntary retreat; retreats below 15% HP | Never attacks players |
| 1 | Bloodthirsty | Any hostile within 12 blocks | Doesn't retreat until HP is below 30% | |
| 2 | Ready and willing | Hostile within 6 blocks; holds its ground | No retreat rule; holds position | |
| 3 | Reasonable | Only when a threat is adjacent and no retreat path exists | Retreats first, if a path exists | Avoids fights; fights when needed |
| 4 | Cornered animal | Only after escape fails | Always flees first | Escape fails: no path, or hurt while retreating |
| 5 | Innocent | Never, even when hit | Flees; walls off with blocks once place_block exists; calls for help below 30% HP | Never swings. Death accepted (D3) |

Terms (proposed):
- hostile: a monster-category mob.
- threat: a hostile within `hostile_range`, or any entity that hurt the agent in the last 3 s.
- adjacent: within 2 blocks.
- escape exists: a path of at most `escape_max_steps` to a tile at least `escape_distance` from every threat.
- hurt while fleeing: damaged in the last 3 s while retreating.

```java
public enum Level { GUNS_BLAZING, BLOODTHIRSTY, READY, REASONABLE, CORNERED, INNOCENT }
public record Situation(boolean targetHostile, boolean targetPlayer, double distance,
                        boolean adjacent, double hpFraction, boolean escapeExists,
                        boolean hurtWhileFleeing) {}
public record Verdict(boolean allowed, String reason) {}
```

### 7. Guardrails
- Kill switch: `/cp stop` or `C stop` cancels the queue, halts the loop, and blocks model calls until the next allowlisted order.
- Call rate: at most `max_calls_per_minute`.
- Token cap: when `session_token_cap` is reached, calls stop and chat says so. Resets on server restart. Cache reads count at a tenth (`Brain.Usage.weighted()`); `total()` is unchanged for logs.
- Step cap: `max_steps` tool rounds per order.
- Break natural blocks anywhere; other blocks only if Claude placed them (`Guardrails.checkBreak`, `body/NaturalBlocks`). Place anywhere free.
- Unknown tool names are denied with `denied:unknown_tool`.

### 8. Navigation
- v1: vanilla navigation toward a target. No path gives `failed:no_path`. 10 s without arriving gives `failed:timeout`.
- EscapePlanner uses the same pathing. A custom search is added only if vanilla navigation can't answer the escape question (decided in Phase 4).
- Each tick, move_to probes 0.8 blocks ahead of the body. Lava, fire, or a drop that would kill refuses the move: the body halts and the move fails with `failed:hazard_lava`, `hazard_fire` or `hazard_drop`. A drop onto water is safe.

### 9. Interrupts
- Interrupts: a threat (a hostile within `hostile_range` in a straight line, height included, or any hit on the body), a new order, or `stop`.
- With `reflexes` on, code acts first. `Reflexes.decide` turns the level's stance into `flee` or one `attack_entity` swing (only within 3 blocks, and only at `tool_phase` 4 or higher), queued at level 3. A lit creeper is always fled from. Players and non-hostile mobs never trigger a reflex. The interrupt then tells Claude what ran, what was paused and what was scrapped; otherwise the queue is left alone. HOLD, or FIGHT out of reach, is an interrupt with no reflex.
- With `reflexes` off, a threat interrupt cancels everything below level 3, sends results at once (unfinished actions report `cancelled`), and starts a new message.
- Threat interrupts are debounced: one per threat, and no more than one every 5 seconds. Lit creepers have their own tracker with no gap. The scan runs every 2 ticks.
- Chase rule: with `reflexes` on and no level 3 running or waiting, a hostile within 6 blocks re-runs the reflex decision at most once per mob every 2 s. A FLEE or FIGHT is queued as a reflex; NONE sends nothing. The rule is quiet: only the first reflex for a threat (the `near:`, `lit:` and `hit:` paths) tells the model. A re-fire only queues its level 3 actions, with no interrupt and no reaction call (`AgentLoop.reflexQuiet`).
- Reflexes still run after `C stop`; no model call follows while the kill switch is on.
- An interrupt during a model call: the reply's tool calls are answered `cancelled` and the interrupt goes out with them. With no order running, an interrupt starts a reaction turn (`idle_reactions`), unless the kill switch is on.
- Each interrupt carries the caution policy's advice (fight, hold, flee, flee and call for help).

### 10. Memory
- Short-term: exact, recent observations, e.g. "chest at 10, 64, 5: 15 oak_log, 17 cobblestone, 64 torch (3/27 slots used)". Kept in RAM, one per subject, oldest dropped past `memory_short_capacity`, faded after `memory_short_minutes`. `inspect_container` writes one automatically; the model writes them with `note`.
- Long-term: durable summaries the model chooses to keep with `remember`, e.g. "chest@10,64,5: building materials: logs, cobblestone, torches". Saved on every change to `<world>/claudeplayer/memory.json`, readable and editable by F. When full, a new subject fails with `failed:memory_full` until something is forgotten.
- `forget` removes a subject from both. Both memories appear as a `<memory>` data block in each order's first turn (about 3000 characters at most, newest first). `/cp memory` shows them.
- Memory is text the model wrote, possibly while reading world text, so it is cleaned and capped like world text and marked as data in the prompt.

## KEYS
Config (`Config.java`). A file that holds a key stays out of version control.

| Key | Default | Meaning |
|---|---|---|
| `brain` | `claude_code` | `claude_code` (Claude Code CLI, subscription) or `api` (Messages API, key) |
| `claude_code_path` | empty | Path to `claude.exe`; empty searches PATH, then `~/.local/bin` |
| `claude_code_timeout_seconds` | `90` | One Claude Code turn |
| `claude_code_idle_minutes` | `10` | Stop a warm session or spare unused this long |
| `model` | `claude-haiku-5-5` | Model for either brain |
| `api_key_env` | `ANTHROPIC_API_KEY` | Env var holding the key |
| `body_name` | `Claude` | Fake player display name |
| `trigger` | `C` | Order prefix. A letter trigger is case-sensitive and needs a space after it; `<body_name>:` always works too |
| `orders_allowlist` | empty | Player names allowed to give orders |
| `default_level` | `3` | Caution level at spawn |
| `actions_per_second` | `3` | Start rate |
| `batch_size` | `12` | Max actions per model reply |
| `prefetch_at` | `4` | Waiting actions in a batch that trigger the results message |
| `max_steps` | `8` | Tool rounds per order |
| `max_calls_per_minute` | `12` | Call rate cap |
| `session_token_cap` | `200000` | Placeholder; F sets |
| `hostile_range` | `16` | Threat radius |
| `escape_distance` | `8` | Safe distance from threats |
| `escape_max_steps` | `12` | Max retreat path length |
| `effort` | `low` | `output_config.effort`; empty uses the model default (added) |
| `max_tokens` | `4096` | Per reply; thinking counts against it (added) |
| `request_timeout_seconds` | `20` | HTTP timeout |
| `prompt_caching` | `true` | Cache breakpoints on the system block and the newest turn |
| `log_prompts` | `false` | Full request bodies at DEBUG |
| `action_timeout_seconds` | `10` | Timed action timeout |
| `tool_phase` | `2` | Highest plan phase whose tools Claude can call |
| `idle_reactions` | `true` | React to threats with no order running |
| `auto_eat` | `true` | Eat on its own when hungry and safe (no model call) |
| `autonomy_interval_seconds` | `60` | At most one autonomous goal turn per interval |
| `reflexes` | `true` | Code flees or fights at level 3 before the model is told |
| `memory_short_capacity` | `24` | Short-term notes kept |
| `memory_short_minutes` | `10` | Minutes before a note fades |
| `memory_long_capacity` | `64` | Long-term memories per world |

Commands (`/cp`):

| Command | What |
|---|---|
| `/cp spawn` | Spawn the body at your position; it becomes home |
| `/cp despawn` | Remove the body |
| `/cp respawn` | Respawn after death, at home |
| `/cp level <0-5>` | Set caution level (ops only) |
| `/cp stop` | Halt now |
| `/cp status` | Level, queue, calls and tokens this session |
| `/cp keycheck` | One free count_tokens call to confirm the key and model work |
| `/cp memory` | Show short- and long-term memory |
| `/cp allow <name>`, `/cp deny <name>` | Edit the allowlist (ops) |
| `/cp quickstart` | Allow the caller, bring the body to them (spawn, or fetch it from over 16 blocks away), run `C demo`, report whether a key is set |
| `/cp help`, `/cp` | Instructions with clickable commands |

Anyone can run `/cp help`. Ops and the single-player owner can run every `/cp` command. Allowlisted players can run all of them except `level`, `allow` and `deny`. Players who can command the body get a one-line `/cp help` hint when they join.

Dev launch: `./gradlew runClient -Pquickstart="World"` passes `--quickPlaySingleplayer World` and sets `-Dclaudeplayer.quickstart=true`, which runs `/cp quickstart` 3 s after the first such player joins.

## TOOLS (what Claude can call)
Gated by `tool_phase` (default 2).
- Phases 1 and 2, on by default: `get_state`, `look(radius)`, `move_to(x, y, z)`, `flee`, `say(text)`, `wait(seconds)`, `stop`; memory `note`, `remember`, `forget`; containers `inspect_container`, `store(x,y,z,item,count)`, `take(x,y,z,item,count)`; `eat`, `follow_player(name, seconds)`; blocks `mine_block(x,y,z)`, `place_block(x,y,z,item)`, `equip(item)`; `craft(item, count)` (3x3 needs a table within 4.5), `smelt(item, count)` (loads a furnace and returns; collect with `take`); routines `gather(target, count)` (groups: log, stone, coal, iron, ...) and `dig(direction, length)` (tunnel or staircase, stops at fluids). Break rule: natural blocks anywhere, others only if Claude placed them. `inspect_container` reads the containers listed in `world_state.points` (within 6 blocks); an ender chest fails with `failed:ender_chest_personal`. `move_to` to a player uses the `pos` in `world_state.players`.
- Phase 3: `follow_player(name, seconds)`. Phase 4: `attack_entity(entity_id)`, behind CautionPolicy.
- Phase 6, stubs: `mine_block`, `place_block`, `equip`, `craft`. The work-area check is live; the edit itself returns `failed:not_implemented`.
- Never tools: change caution level, edit the allowlist, run commands, touch config. ToolParseTest checks it.
- Every tool takes an optional `prio`: 1 routine (default), 2 important (paused and resumed after danger), 3 safety. Only `flee`, `stop` and `attack_entity` may ask for 3; anything else at 3 runs at 2, and a `prio_lowered` note goes out in the next turn.
- Results are short codes: `ok`, `denied:<reason>`, `failed:<reason>`, `cancelled`, `scrapped` (level 1 work dropped by a level 3 entry), `queued`.

## GAME FACTS
COMPILED means the code compiles against forge-1.20.1-47.4.28 with official mappings. In-game behavior still needs Phase 0; mark lines IN-GAME once F confirms them.
- COMPILED: `ServerChatEvent#getPlayer`, `#getRawText`; `RegisterCommandsEvent`; `TickEvent.ServerTickEvent` (`phase`); `LivingHurtEvent`; `ServerStartedEvent` and `ServerStoppingEvent` (net.minecraftforge.event.server).
- Fake player: Forge's `FakePlayer` makes `tick()` and `die()` no-ops and is invulnerable, so it can't be the body. The body is `AgentPlayer extends ServerPlayer`, joined with `PlayerList#placeNewPlayer(Connection, ServerPlayer)` on a `NullConnection` inside an `EmbeddedChannel` (Forge's login code reads the channel), and runs `doTick()` itself. COMPILED. Spike A must confirm movement, damage, death and visibility in game.
- Players have no pathfinder: a `Zombie` that is never added to the world plans paths (`PathNavigation#createPath(BlockPos, int)`), and the body steers with `zza` and `setJumping`. COMPILED.
- Hand-off: `MinecraftServer#tell(new TickTask(tick, runnable))` always queues. `execute` runs inline when called on the server thread, and after shutdown. COMPILED. Lag slows real time, so pacing counts ticks.
- Attacks go through `Player#attack`, gated by `getAttackStrengthScale`, so the vanilla cooldown applies and 3 per second is an upper bound. COMPILED.
- Gson (2.10) ships with Minecraft, and java.net.http with Java 17. COMPILED, and the unit tests use both.
- Server chat broadcast: `PlayerList#broadcastSystemMessage(Component, boolean)`. COMPILED.
- Double chests: `ChestBlock.getContainer(block, state, level, pos, true)` returns both halves. COMPILED.
- COMPILED: `Creeper#getSwellDir`, `Creeper#isIgnited`; hazard probe 0.8 blocks ahead.
- `/forge tps`: not checked yet.
- Anthropic Messages API (checked against the claude-api reference on 22 Oct 2026): `POST /v1/messages` with headers `x-api-key` and `anthropic-version: 2026-10-21`. Tool results come first in the user turn, with `is_error` on failures. Stop reasons include `end_turn`, `tool_use`, `max_tokens` and `refusal`. `claude-haiku-5-5` runs adaptive thinking by default (default effort medium), so assistant content is replayed unchanged and transcripts are append-only. Sampling parameters are rejected on 2026-10-21. The minimum cacheable prefix on Haiku 5.5 is 512 tokens. `/v1/messages/count_tokens` backs `/cp keycheck`.
- Claude Code plugins (Phase 6 only): structure, and where commands and skills go. Check the Claude Code docs first.

## STATUS
- 22 Oct 2026: planning pass done (Haiku 5.5 Max).
- 22 Oct 2026: skeleton, rules and brain built (Opus 5.5). Every LAYOUT file exists. Phase 1 and 2 code is in, plus memory; phase 3 and 4 code sits behind `tool_phase`. `./gradlew build` is green with 85 unit tests. Nothing has run in Minecraft yet.
- 22 Oct 2026: piece 1 built (priority list and reflexes): queue levels 1 to 3, `prio` on every tool, the `flee` tool, code reflexes, and moves that refuse lava, fire and deadly drops. `./gradlew build` is green with 118 unit tests. In-game checks M1 to M5 are in the handoff.
- 22 Oct 2026: piece 1 merged into dev with the Claude Code brain (`brain = "claude_code"`). `./gradlew build` is green with 125 unit tests.
- 22 Oct 2026: Claude Brain screen built and merged into dev: right-click the body for a read-only chest of the queue, for the players who may use `/cp`. `./gradlew build` is green with 127 unit tests. Not in game yet.
- 22 Oct 2026: playtest 1 fixes on dev: player positions and nearby points of interest in the snapshot, replies up to 600 characters, a 16-block straight-line reflex radius, a quiet 2 s re-flee while chased, `ender_chest_personal`, cache reads counted at a tenth for the token cap, and prompt rules. `./gradlew build` is green with 134 unit tests. Not checked in game yet.
- 23 Oct 2026: origin/dev (skills, map view, `ChatLines`, warm sessions, autonomy, gather and dig) merged with the playtest 1 fixes. F's `ChatLines` replaced the 600-character replies; `points` and player `pos` stay beside `map`. `./gradlew build` is green with 206 unit tests. Not checked in game yet.
- Next: F runs the Phase 0 checks against this code. Spike A is `/cp spawn` plus `C come here`; Spike B is `/cp keycheck` with a bad key. Details in the handoff.

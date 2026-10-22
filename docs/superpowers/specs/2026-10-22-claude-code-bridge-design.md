# Claude Code bridge, priority list, Blockman: design

Written 22 Oct 2026 from a brainstorm with F. Plan option B ("Claude Code as the brain") becomes real, and the tradeoffs it brings are handled by a middle man, the **bridge**. Two harness changes come with it: a **priority list** with code reflexes, and **Blockman**, a short-reply prompt mode that also keeps Claude on task.

## 1. Goal

Run Claude on F's Claude Code subscription instead of an API key, without the costs of doing that naively:

| Tradeoff | Fix |
|---|---|
| Drains the weekly limit | Haiku 5.5, a small replacement system prompt, Claude Code's own caching, a usage ledger with warnings, caveman past 80% |
| Cold calls | One warm `claude` process per order, a spare one started before the order, tool calls dispatched as each block closes |
| Credentials | Claude Code keeps its own sign-in. Neither the mod nor the bridge reads it. `ANTHROPIC_API_KEY` is scrubbed from the child, so it never bills the API |
| Tool format | The bridge serves the game tools over MCP and holds each call open until the mod's AgentLoop has run it |
| Security | The lessons of the Claudefox 0.10 review (section 9) |

Success: `claude: come here` runs on the subscription with no key set. A creeper approach starts a flee in under one server tick of being seen, without waiting for the model. Usage warnings arrive at 80% and 90%. `./gradlew build` is green.

## 2. Decisions (stated by F, 22 Oct)

- Same PC only. Nothing listens beyond this machine.
- Model: always Haiku 5.5 at high effort.
- Think harder: when the player asks, Claude offers `(y)/(n)` to switch to Haiku 5.5 at max effort. No offer while a hostile is within 16 blocks horizontally.
- Usage: measure the real Claude Code limit. Warn at 80% and again at 90%. From 80% on, turn on the real caveman skill. Never block; Claude Code itself refuses at 100%.
- Threats: fight or flee by caution level, at once, in code.
- Priority: three levels. A level 3 always goes to the front, level 2 is paused, level 1 is scrapped.
- Blockman: short replies, and a guardrail so Claude doesn't lose track of where it is and what it is doing.
- Build on the Claudefox bridge pattern: a helper, a tool server Claude Code talks to with a per-session token, one warm `claude` per chat. Make it faster.

- Confirmed by F with the spec (22 Oct): `brain` defaults to `claude_code`; "16 blocks horizontally" is XZ distance with no vertical cap; the bridge is written in Java.

## 3. Facts the design rests on

Checked on this PC with Claude Code 2.1.296:

- Each turn with `--output-format stream-json --verbose` emits a `rate_limit_event` carrying `unifiedWindows.five_hour` and `unifiedWindows.seven_day`, each with `utilization` (0 to 1) and `resetsAt` (epoch seconds). F's read 0.10 and 0.52.
- The `result` event carries `usage` (input, output, cache write, cache read) and `total_cost_usd`.
- Flags exist: `--system-prompt`, `--tools`, `--strict-mcp-config`, `--mcp-config`, `--setting-sources`, `--plugin-dir`, `--effort`, `--model`, `--input-format stream-json`, `--include-partial-messages`, `--resume`.
- A bare `claude -p` with no tools still wrote about 3.5k cache tokens of Claude Code's own prompt. `--system-prompt` replaces it.

In the mod today:

- `Brain` is request→reply (`core/brain/Brain.java`). AgentLoop drives: it parses tool calls, enqueues them, reports a batch when done or at the prefetch point (`prefetchAt`, reporting `queued` for calls not yet run), and sends the next turn.
- `AgentLoop.interrupt` cancels the whole queue and asks the model. No code acts on a threat before the model answers; `DamageWatcher` only writes the advised stance into the interrupt text.
- `ActionQueue` is first-in-first-out. Only `Stop` drops the waiting entries.
- There is no flee action. `EscapePlanner` only answers `escapeExists`.
- `SnapshotBuilder.hostiles` uses straight-line distance (`hostileRange` 16).

Not yet confirmed: the spikes in section 11.

## 4. Architecture

```
 Minecraft server JVM                         bridge JVM (child of the game)           claude (child of the bridge)
 ┌───────────────────────────┐  AF_UNIX      ┌─────────────────────────────┐ spawn    ┌──────────────────────────┐
 │ AgentLoop ─ ClaudeCodeBrain├──socket──────►│ BridgeMain                  ├─────────►│ claude -p, stream-json   │
 │  Guardrails, CautionPolicy │  hello+token  │  Sessions (one per order,   │ stdin/   │  --tools ""              │
 │  ActionQueue (priorities)  │  JSON lines   │   plus one spare)           │ stdout   │  --mcp-config (http)     │
 │  Reflexes                  │◄──────────────┤  McpServer 127.0.0.1:<port> │◄─────────┤  game tools only         │
 └───────────────────────────┘               │  UsageLedger                │  HTTP MCP└──────────────────────────┘
                                              └─────────────────────────────┘  bearer
```

- **Mod.** `ClaudeCodeBrain implements Brain`. The loop, policy and guardrails don't move. `config brain = "api" | "claude_code"` picks the backend.
- **Bridge.** A separate Java 17 process, built by a `bridge/` Gradle subproject. Its jar ships inside the mod jar. At start the mod copies it to `%LOCALAPPDATA%\claudeplayer\bridge-<sha256>.jar`, checks the hash against the one recorded at build time, and launches it with the game's own `java` (`ProcessHandle.current().info().command()`). Nothing extra to install.
- **Claude Code child.** One per order, plus one spare. Locked down (section 9).

Why a separate process, not code in the game: it matches the proven Claudefox shape, keeps process supervision and HTTP out of the server JVM, and a bridge crash can't take the world down. Section 9 covers the cost, two local channels.

## 5. Order flow

1. The bridge keeps one **spare** `claude` started and idle on stream-json input, so an order skips startup.
2. `startOrder` → `ClaudeCodeBrain.send` with the opening turn. The brain sends `order` (the opening text) on the socket. The bridge hands the spare to this order and starts a new spare.
3. Claude streams. With `--include-partial-messages`, the bridge sees each `tool_use` block close. It forwards it as `tool_call {id, name, input}` at once. `ClaudeCodeBrain` passes it to the loop through a new optional listener on `Brain.send`, and the loop enqueues it with its priority (section 6). `ApiBrain` ignores the listener in v1.
4. Claude Code calls the MCP tool. The bridge **holds the call open** and matches it to the `tool_use` id (spike S3).
5. The assistant message ends. The bridge sends `reply`, and the brain returns a `Brain.Reply` as today. `content` is the assistant content as streamed.
6. The loop runs the calls through `ToolSchemas.parse`, the policy and the queue, exactly as now. At batch end or the prefetch point it sends the next user turn.
7. `ClaudeCodeBrain` sends only what's new since the last call: the tool results and the turn text. It checks that the transcript prefix is unchanged; transcripts are append-only, and a mismatch restarts the session and is logged. The bridge answers each held MCP call with its result and appends the turn text (snapshot, notes, Blockman block) to the last one. A turn with no held calls (the opening, an interrupt after a final text) goes in as a stream-json user message instead.
8. A reply with no tool calls ends the order as today. The bridge stops that `claude` process.

An idle session is stopped after `idle_stop_minutes` (10). A stopped order kills its process tree at once.

## 6. Priority list and reflexes (both brains)

**Levels.** Every queue entry has `prio` 1, 2 or 3. One action runs at a time. The highest level goes first; within a level, first in first out. A level-3 entry starts in the tick it arrives and doesn't wait for pacing credit; the 3-per-second pace still applies to everything after it.

| Level | Examples | When a level 3 arrives |
|---|---|---|
| 3 | run from a creeper, fight back, `stop` | goes to the front at once |
| 2 | "build a shelter before night", the order's key steps | **paused**. A running level-2 action is cancelled and put back at the head of level 2 to restart afterwards |
| 1 | collect crops, tidy chests, follow | **scrapped**, reported `scrapped` |

**Who sets it.**
- Claude tags each tool call with an optional `prio`, 1 or 2 (default 1). It goes into every schema in `ToolSchemas`.
- Level 3 is accepted only on the safety tools `flee`, `stop` and `attack_entity` (CautionPolicy still checks every swing). No eat tool exists yet.
- Anything else asked at 3 runs at 2. A `prio_lowered` note goes out in the next turn; the result code stays one code.
- Code reflexes always enqueue at 3.

**Reflexes** run on the server thread in the tick that sees the danger. No model call comes first:
- **Hostile.** Within 16 blocks horizontally (XZ distance, Y ignored): `CautionPolicy.advise` picks FIGHT, HOLD, FLEE or FLEE_AND_CALL_FOR_HELP, and the reflex enqueues that at level 3. A creeper with a lit fuse is FLEE at every level. `ThreatTracker` still debounces.
  - FIGHT is a single swing, and only when the threat is within attack reach (3 blocks). Farther away, Claude is told and plans the approach.
  - FIGHT while attacks are off (`tool_phase` < 4) becomes FLEE.
  - Players and non-hostile mobs never trigger a reflex.
- **Hazard.** When a move's next step enters lava, fire or a drop that would kill, the move is refused: the body halts and the move fails with `failed:hazard_lava`, `hazard_fire` or `hazard_drop`. There is no separate level-3 `stop` entry, because a stop would also cancel paused level-2 work. Each later move runs the same check.
- Reflexes still run after `claude: stop`. They protect the body and its items. No model call follows while the kill switch is on.
- After either one, the loop sends Claude an `INTERRUPT` note listing what ran, what was paused and what was scrapped. When a call is already out, the existing `interruptInFlight` path applies. Claude plans its follow-up while the reflex is already running.

**New pieces.**
- A `Flee` action and `flee` tool, driven by a new `EscapePlanner` method that returns a destination.
- Priorities in `ActionQueue`.
- A `Reflexes` class in `core/safety`: pure, fed by the snapshot, unit-tested.
- `AgentLoop.interrupt` changes from "cancel everything" to "apply the level-3 rules".

## 7. Blockman and caveman

**Blockman** is a section of the system prompt, always on for both brains. It isn't a skill: a skill loads only when Claude decides to read it, and a guardrail has to be in context every turn.
- **Short.** Chat lines of 15 words or fewer. No narration beside tool calls, no recaps.
- **Guardrail.** Every user turn ends with a pinned block built by `PromptBuilder` from the snapshot and the queue:
  ```
  ORDER: "farm wheat" (from Felipe) | STEP: 2/4 replant row 3
  POS: 120 64 -30 overworld | HP 18/20 | FOOD 15/20
  THREATS: none | QUEUE: p2 move_to, p1 harvest x3
  ```
  The rule in the prompt: if the next action doesn't serve ORDER, stop and say why. `STEP` comes from a `note` with subject `step`, which Claude keeps current. The block is appended to each turn and never written into the system prompt, so caching and the append-only rule hold. All world text in it passes through `WorldText`.

**Caveman** is the real skill, at F's request.
- The bridge builds a private one-skill plugin under `%LOCALAPPDATA%\claudeplayer\plugins\caveman\`, from F's installed `caveman/SKILL.md`. It looks under `~/.claude/skills/`, or at `caveman_skill_path` if set. It logs the file's SHA-256 and shows it in `/cp status`.
- Every `claude` gets `--plugin-dir` for that plugin only. `--setting-sources ""` stays, so F's other plugins, hooks and CLAUDE.md never load.
- At 80%, the bridge sends `/caveman` as a user message at the next turn boundary (spike S5). It's appended, so it can switch on mid-order. It stays on until that window resets below 80%.

## 8. Usage ledger and think-harder

**Ledger** (`UsageLedger` in the bridge, file `%LOCALAPPDATA%\claudeplayer\usage.json`)
- **Stores** the last `utilization` and `resetsAt` per window, the warnings fired per window, and token totals per order and per day.
- **Sends** `usage {five_hour, seven_day}` after each turn. `/cp status` shows the windows and tokens.
- **Warns** when either window crosses 0.80, then 0.90. The chat line goes to online allowlisted players, e.g. `Claude usage: 7-day at 81%, resets Mon 14:00`. Each threshold fires once per window and re-arms when `resetsAt` passes.
- **At 100%** a refusal from Claude Code ends the order with `Claude Code limit reached, resets <time>`. There is no silent fallback to the API key.
- The mod's existing `sessionTokenCap` guardrail keeps working on the reported tokens.

**Think-harder**
- **Trigger.** The order contains a phrase from `think_harder_phrases` (default `think harder`, `think hard`; F adds more).
- **Gate.** A hostile within 16 blocks horizontally means no offer, and Claude says `Not now: zombie 9 blocks away.`
- **Offer.** To the ordering player only: `Switch to Haiku 5.5 max for this order? claude: y / claude: n`. While the offer is open, `ChatIntake` reads `y`/`n` from that player as the answer, not as a new order. No answer in 30 s counts as no.
- **Yes.** Max effort for this order, through per-turn effort or a `--resume` respawn (spike S4). Back to high when the order ends. A hostile within 16 blocks while on max fires the reflex at once, and the next turn drops back to high.
- **Allowed values.** The bridge accepts only `high` and `max` as effort and only the configured model id. The protocol carries enums, never raw CLI arguments.

## 9. Security

Each line answers a Claudefox 0.10 review item, or the same risk in this project.

| Risk | Answer |
|---|---|
| World text steers Claude (review A, "the page commands, Claude obeys") | `WorldText` quoting is unchanged. Claude Code has no tool except the game tools, and every call still goes through ToolSchemas, Guardrails and CautionPolicy in the mod |
| Claude Code's own powers (B1, B6) | `--tools ""` (no Bash, Edit, Read, WebFetch), `--strict-mcp-config` with only our server, `--setting-sources ""`, `--plugin-dir` for caveman only, `--permission-mode dontAsk` with `--allowedTools` limited to our MCP tools, never `--dangerously-skip-permissions`, cwd an empty folder under `%LOCALAPPDATA%\claudeplayer\work\` |
| Free-form model or flags (B13) | Enums only on the protocol; the model id comes from config |
| Local channel 1, mod ↔ bridge (pipe-token) | A Unix-domain socket in `%LOCALAPPDATA%\claudeplayer\`. A 32-byte random token, handed to the bridge on stdin (never on argv), checked in constant time in the hello. One client only; a second connection is refused |
| Local channel 2, Claude ↔ MCP | Bound to `127.0.0.1` on a random port. A bearer token per `claude` process, written into that process's `--mcp-config` file in the private folder and deleted when it exits. Host must be `127.0.0.1:<port>`. A request with an `Origin` header is refused, which stops browser and DNS-rebinding requests |
| Oversized messages (B11) | 1 MB cap per frame both ways; over the cap, the frame is drained and refused with an error |
| Test hooks in release (B9) | A fake `claude` path and short timers work only with `-Dclaudeplayer.test=true`. Under test, the real `claude` never starts |
| Orphan processes (B10) | The bridge records child pids in the private folder and kills the recorded trees at start, after checking the image name. It exits when the socket closes or the game process ends (`ProcessHandle.onExit`) |
| Tampered bridge jar | SHA-256 checked against the build-time value before every launch |
| Credentials and logs | Neither side reads `~/.claude` credentials. The child's environment drops `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` and `CLAUDE_CODE_USE_*`. INFO logs carry ids, stop reasons, tokens and results only, never tokens of either channel or prompts |
| Two accounts (U2) | `/cp status` shows the Claude Code account email from `claude auth status` or the init event, when available |

## 10. Config (`claudeplayer-common.toml`, new keys)

| Key | Default | Meaning |
|---|---|---|
| `brain` | `claude_code` | `api` or `claude_code` |
| `claude_model` | `claude-haiku-5-5` | Model for Claude Code |
| `claude_effort` / `claude_think_effort` | `high` / `max` | Only these two values are accepted |
| `think_harder_phrases` | `["think harder", "think hard"]` | Trigger phrases |
| `think_harder_timeout_seconds` | 30 | Offer timeout |
| `usage_warn_at` | `[0.80, 0.90]` | Warning thresholds |
| `caveman_at` | 0.80 | Caveman from here |
| `caveman_skill_path` | empty | Override for the skill file |
| `spare_process` | true | Keep one idle `claude` ready |
| `idle_stop_minutes` | 10 | Stop an idle session |
| `reflexes` | true | Code reflexes on threats and hazards |

`hostileRange` (16) stays the radius, now measured on XZ for the reflex and the think-harder gate.

## 11. Spikes, before building the bridge

Each one is a short script against the real CLI. The result goes into the modmap GAME FACTS (or a new BRIDGE FACTS list).

| # | Question | If no |
|---|---|---|
| S1 | Does `--mcp-config` accept `{"type":"http","url":...,"headers":{...}}`, and do MCP tools then run under `--tools ""`? | A stdio tool server (Claudefox shape), connecting back over the socket with the token |
| S2 | Does a spare process started with `--input-format stream-json` sit idle until its first line? | Start on order; keep only the idle stop |
| S3 | Can a held MCP call be matched to its `tool_use` id (`_meta`, or strict order)? Does Claude Code wait on a held call without timing out at about 30 s? | Match by order within a message; raise `MCP_TOOL_TIMEOUT` in the child's environment |
| S4 | Can effort change for one turn in a running session? | `--resume <session> --effort max` respawn |
| S5 | Does `/caveman` as a stream-json user message run the plugin skill when `--tools ""` is set? | Read the skill text and send it as a user message |
| S6 | Does a Unix-domain socket work between two JVMs here, on Windows 11 and Java 17? | A named pipe through a small JNA call, or loopback TCP with the same token |

## 12. Testing

- **Pure core (JUnit, as today):** priority ordering, preempt, scrap and pause; `prio` clamping; reflex verdicts for each caution level, the lit creeper and hazards; XZ distance; Blockman block rendering and `WorldText` in it; think-harder gate, timeout and y/n intake; ledger thresholds, once-per-window and re-arm; the transcript-tail diff and the prefix check.
- **Bridge (JUnit, test flag on):** a fake `claude` (a small Java main) that replays recorded stream-json and calls the MCP tools. Tests cover the hello token (wrong, missing, slow), the frame cap, held calls answered in order, the spare handoff, idle stop, the process-tree kill and the orphan sweep, environment scrubbing, refusing a request with `Origin`, and the jar hash refusal.
- **`ToolParseTest`:** a case for `flee` and for `prio` on every tool.
- **PurityTest** still guards `core/`.
- **In game (F):** `claude: come here` with no key set, then a creeper approach (flee starts before Claude's next reply), lava in a path, the usage line in `/cp status`, and think-harder with and without a mob near.

## 13. Build outline (four pieces, in this order)

1. **Priority list and reflexes.** `ActionQueue` priorities, `Flee` and the `flee` tool, the `EscapePlanner` destination, `Reflexes`, the new `interrupt` rules, `prio` in ToolSchemas, the XZ distance. This works with today's API brain, so it lands and gets tested in game first.
2. **Blockman.** The prompt section and the pinned block in `PromptBuilder`, the `step` note. Both brains.
3. **Bridge core.** Spikes S1 to S3 and S6 first. Then the `bridge/` subproject, the socket protocol, sessions with the spare process, the MCP server with held calls, the lockdown flags, the jar copy and hash check, `ClaudeCodeBrain`, the early-call listener on `Brain.send`, and the `brain` config.
4. **Usage and think-harder.** Spikes S4 and S5. Then `UsageLedger`, warnings, caveman, the think-harder offer and the effort switch, and `/cp status` lines.

Each piece has its own plan and gate: `./gradlew build` green, then F's in-game checks for that piece.

**Confirmed by F (22 Oct):** `brain` defaults to `claude_code`; the 16-block XZ radius has no vertical cap (a mob deep in a cave below counts); the bridge is Java, not Node.

## 14. Not in this work

Hosting for other machines, a Node bridge, Claude Code plugins for development (plan Phase 6), and API-brain streaming. `ApiBrain` ignores the early-call listener for now.

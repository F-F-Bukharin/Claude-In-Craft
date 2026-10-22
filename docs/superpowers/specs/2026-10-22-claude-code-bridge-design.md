# Claude Code bridge, priority list, Blockman: design

Written 22 Oct 2026 from a brainstorm with F. Plan option B ("Claude Code as the brain") becomes real, and the tradeoffs it brings are handled by a middle man, the **bridge**. Two harness changes come with it: a **priority list** with code reflexes, and **Blockman**, a short-reply prompt mode that also keeps Claude on task.

Revised 22 Oct, after piece 1 was merged into dev beside F's `ClaudeCodeBrain` (commit e900f7f). That brain already runs `claude -p` locked down and gets tool calls back through a JSON schema. So pieces 3 and 4 now build on it: the bridge is a component inside the mod that keeps warm sessions, and there is no separate process and no MCP server. Sections 1 to 5 and 7 to 14 changed; sections 6 (piece 1, built) and the Blockman half of 7 (piece 2) did not.

## 1. Goal

Run Claude on F's Claude Code subscription instead of an API key, without the costs of doing that naively:

| Tradeoff | Fix |
|---|---|
| Drains the weekly limit | Haiku 5.5 and a replaced system prompt (both already in `ClaudeCodeBrain`). New: a real multi-turn session per order, so each turn hits the prompt cache instead of re-sending the whole transcript as one fresh message; a usage ledger with warnings; caveman past 80% |
| Cold calls | Today every model call starts a new `claude` process. New: one warm process per order, and a spare one started before the order |
| Credentials | Claude Code keeps its own sign-in, and the mod never reads it (already true). New: `ANTHROPIC_API_KEY` and its relatives are scrubbed from the child's environment. Today the child inherits the game's environment, so a key set for `brain = "api"` would make Claude Code bill the API |
| Tool format | Solved in `ClaudeCodeBrain`: `--json-schema` makes Claude answer `{say, calls[]}`, which becomes an ordinary `Brain.Reply`. No MCP needed |
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
  - **Revised 22 Oct, confirmed by F:** what carries over from Claudefox is one warm `claude` per chat on stream-json, the idle stop, `--resume` and the lockdown. The tool server and the separate helper process don't. `ClaudeCodeBrain` already gets tool calls through `--json-schema`, so a helper process would only add a local channel to secure. F asked for pieces 3 and 4 to build on that brain.

- Confirmed by F with the spec (22 Oct): `brain` defaults to `claude_code` (dev already does this); "16 blocks horizontally" is XZ distance with no vertical cap; the bridge is written in Java (it is now Java inside the mod).

## 3. Facts the design rests on

Checked on this PC with Claude Code 2.1.296:

- Each turn with `--output-format stream-json --verbose` emits a `rate_limit_event` carrying `unifiedWindows.five_hour` and `unifiedWindows.seven_day`, each with `utilization` (0 to 1) and `resetsAt` (epoch seconds). F's read 0.10 and 0.52.
- The `result` event carries `usage` (input, output, cache write, cache read) and `total_cost_usd`.
- Flags exist: `--system-prompt`, `--tools`, `--strict-mcp-config`, `--mcp-config`, `--setting-sources`, `--plugin-dir`, `--effort`, `--model`, `--input-format stream-json`, `--include-partial-messages`, `--resume`.
- A bare `claude -p` with no tools still wrote about 3.5k cache tokens of Claude Code's own prompt. `--system-prompt` replaces it.
- In that probe, rate limits came only as a separate `rate_limit_event` line; the final `result` event had none. `--output-format json` prints only the result, so the ledger needs `--output-format stream-json --verbose` (spike S5 confirms).
- F's caveman skill is installed at `~/.claude/skills/synced/<ids>/caveman/SKILL.md` (seen 22 Oct).

`ClaudeCodeBrain` on dev (`core/brain/ClaudeCodeBrain.java`, e900f7f):
- **One process per model call.** `claude -p --output-format json --tools "" --strict-mcp-config --setting-sources "" --no-session-persistence --disable-slash-commands --system-prompt-file <file> --json-schema <schema> --model <model> --effort <effort>`, run on the brain thread with stdout and stderr redirected to files and a `claude_code_timeout_seconds` (90) timeout.
- **Fresh session each call.** The whole Messages transcript is rendered as one plain-text prompt ("[game]" and "[you]" turns, "result of <tool>: ..."), so every call re-sends everything as a single new message. Only the system prompt can hit the cache across calls (spike S3 measures it).
- **Tool calls.** The schema is `{say: string, calls: [{name, input}]}`. Calls get ids `cc<n>_<i>`, and an empty `calls` ends the turn. The tools' names, descriptions and input schemas are listed in the system prompt file.
- **Working folder.** `java.io.tmpdir/claudeplayer-claude-code`, outside any project, so no CLAUDE.md loads.
- **Settings.** `brain` (default `claude_code`), `claude_code_path` (empty searches PATH, then `~/.local/bin`), and `claude_code_timeout_seconds`. `model` and `effort` are shared with the API brain; `effort` defaults to `low`.
- **Errors.** `NO_CLI` and `CLI` are the new `BrainException` kinds. `api_error_status` 429 maps to RATE_LIMITED and 529 to OVERLOADED. `/cp keycheck` makes one tiny call.
- **Process handling.** `destroyForcibly` on timeout kills only the process, not its children. The environment is inherited from the game. Nothing records child pids.

In the mod today:

- `Brain` is request→reply (`core/brain/Brain.java`). AgentLoop drives: it parses tool calls, enqueues them, reports a batch when done or at the prefetch point (`prefetchAt`, reporting `queued` for calls not yet run), and sends the next turn.
- `AgentLoop.interrupt` cancels the whole queue and asks the model. No code acts on a threat before the model answers; `DamageWatcher` only writes the advised stance into the interrupt text.
- `ActionQueue` is first-in-first-out. Only `Stop` drops the waiting entries.
- There is no flee action. `EscapePlanner` only answers `escapeExists`.
- `SnapshotBuilder.hostiles` uses straight-line distance (`hostileRange` 16).

Not yet confirmed: the spikes in section 11.

## 4. Architecture

```
 Minecraft server JVM                                         claude (children of the game: one per order, one spare)
 ┌───────────────────────────────────────────────┐ spawn    ┌──────────────────────────────────────┐
 │ server thread: AgentLoop, Guardrails,          │          │ claude -p                            │
 │   CautionPolicy, ActionQueue, Reflexes         │          │  --input-format  stream-json         │
 │        │ Brain.send (blocking)                 │ stdin    │  --output-format stream-json --verbose│
 │ brain thread: ClaudeCodeBrain                  ├─────────►│  --json-schema {say, calls[]}        │
 │   ClaudeCodeSessions  warm process per order,  │ JSON     │  --tools "" --strict-mcp-config      │
 │                       spare, idle stop, kill   │ lines    │  --setting-sources ""                │
 │   UsageLedger         rate_limit_event → warn  │◄─────────┤  --disable-slash-commands            │
 │   ClaudeCodeEnv       scrubbed env, pid record │ stdout   │  --system-prompt-file, scrubbed env  │
 └───────────────────────────────────────────────┘          └──────────────────────────────────────┘
```

- **Mod.** `ClaudeCodeBrain` keeps its public face (`send`, `check`, `executable`), its schema and its parsing. The loop, policy and guardrails don't move. `brain = "api" | "claude_code"` still picks the backend.
- **The bridge** is three small classes beside `ClaudeCodeBrain` in `core/brain/`. They are pure Java, process and file I/O only, and PurityTest still holds.
  - `ClaudeCodeSessions` owns the warm processes.
  - `UsageLedger` owns the limits and the warnings.
  - `ClaudeCodeEnv` owns the environment, the pid record and the process-tree kill.
- **Claude Code child.** One per order, plus one spare, locked down as today (section 9).

Why not a separate helper process (the first version of this spec): with tool calls coming back through `--json-schema`, a helper would carry nothing a class can't. It would add a socket and a token to secure (Claudefox review, pipe-token), a jar to ship and check, and a lifecycle to supervise. Process supervision stays off the server thread, because the brain thread already runs it.

## 5. Order flow

1. **Spare.** `ClaudeCodeSessions` keeps one spare `claude` started and idle on stream-json input, so an order skips startup (spike S2). The spare is built with the current system prompt file and flags. When either changes (caveman switched on, the effort changes), the spare is replaced.
2. **Opening.** `startOrder` → `ClaudeCodeBrain.send` with a one-message transcript. The brain takes the spare for this order, starts a new spare, and writes the opening turn as one stream-json user line.
3. **Reply.** The brain reads stdout lines until the turn's `result` event, keeping the `rate_limit_event` for the ledger. The turn's `structured_output` goes through today's `parseResult` mapping into a `Brain.Reply` with `{say, calls}`. The loop runs the calls through `ToolSchemas.parse`, the policy and the priority queue, exactly as now.
4. **Next turns.** Each later `send` writes only what's new since the session's last turn: the tool results by name (today's `renderBlock` format) and the turn text (snapshot, notes, Blockman block), as one user line. The brain keeps the count of messages it has sent. It checks that the request's transcript starts with the session's: transcripts are append-only, so a mismatch ends the session, starts a fresh one from the whole rendered transcript, and logs it.
5. **End.** A reply with no calls ends the order as today, and the brain stops that process.
   - **Dropped order** (a new order, `stop`, an error, a cap): the next `send` won't extend the session, so step 4's check ends it. `AgentLoop.abandon()` also calls a new `Brain.reset()`, a default no-op that `ClaudeCodeBrain` uses to kill the session at once. It only destroys processes, so it is safe from the server thread.
   - **Idle stop:** a session idle for `idle_stop_minutes` (10) is stopped.
   - **Killing** always takes the whole process tree.
6. **Fallback** if S1 fails (no structured output per turn in a stream-json session): keep one process per call, but drop `--no-session-persistence` and pass `--resume <session_id>` from the previous turn, sending only the new turn. Claude Code then sends a real multi-turn transcript, so the cache still hits; only startup stays cold. The session files live in the private working folder and are deleted when the order ends.

Not kept from the first version: dispatching each tool call as it streams. With `--json-schema` the calls arrive as one structured answer at the end of the turn. The time-critical case, danger, is now handled by code reflexes (section 6), which don't wait for Claude.

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

**Caveman** is the real skill's text, at F's request.
- Loading the skill as a plugin would mean dropping `ClaudeCodeBrain`'s `--disable-slash-commands`, adding `--plugin-dir`, and sending `/caveman`. Instead, the brain reads F's installed `caveman/SKILL.md` and uses its body (after the front matter) as the rules. It looks under `~/.claude/skills/` (newest match), or at `caveman_skill_path` if set. The text is capped at 8 KB. Its SHA-256 is logged and shown in `/cp status`. No skill file found means no caveman, and one log line says so.
- **On at 80%.** For orders that start past 80%, the rules are a section at the end of the system prompt file (the spare is rebuilt with it). For an order already running, they go in as one appended user note at the next turn boundary, so the transcript stays append-only.
- **Off again** for the next order once both windows are back below 80% (a window resets).
- **Confirmed by F (22 Oct):** the real skill's rules, not the plugin mechanism. Switching to the plugin later would mean dropping `--disable-slash-commands`, adding `--plugin-dir`, and a spike that `/caveman` runs in a stream-json session.

## 8. Usage ledger and think-harder

**Ledger** (`core/brain/UsageLedger`, file `config/claudeplayer_usage.json` beside the mod's config)
- **Stores** the last `utilization` and `resetsAt` per window, the warnings fired per window, and token totals per order and per day. The file is written atomically (temp file, then rename).
- **Updated** on the brain thread from each turn's `rate_limit_event` and `result.usage`. It is thread-safe. `AgentRuntime` polls it once per tick and posts any pending warning on the server thread. `/cp status` shows the windows and tokens.
- **Warns** when either window crosses 0.80, then 0.90. The chat line goes to online allowlisted players, e.g. `Claude usage: 7-day at 81%, resets Mon 14:00`. Each threshold fires once per window and re-arms when `resetsAt` passes.
- **At 100%** a refusal from Claude Code ends the order with `Claude Code limit reached, resets <time>`. There is no silent fallback to the API key.
- The mod's existing `sessionTokenCap` guardrail keeps working on the reported tokens.

**Think-harder**
- **Trigger.** The order contains a phrase from `think_harder_phrases` (default `think harder`, `think hard`; F adds more).
- **Gate.** A hostile within 16 blocks horizontally means no offer, and Claude says `Not now: zombie 9 blocks away.`
- **Offer.** To the ordering player only: `Switch to Haiku 5.5 max for this order? claude: y / claude: n`. While the offer is open, `ChatIntake` reads `y`/`n` from that player as the answer, not as a new order. No answer in 30 s counts as no.
- **Yes.** Max effort for this order. In a warm session that is a per-turn effort change, or a `--resume <session> --effort max` respawn (spike S4); in the fallback it is just the next call's flag. Back to high when the order ends. A hostile within 16 blocks while on max fires the reflex at once, and the next turn drops back to high.
- **Allowed values.** The Claude Code brain uses `claude_effort` (`high`) and `claude_think_effort` (`max`), validated by the config spec. The API brain keeps its own `effort` (`low`). The model id comes from config. Nothing from chat or the model reaches the command line.

## 9. Security

Each line answers a Claudefox 0.10 review item, or the same risk in this project.

| Risk | Answer |
|---|---|
| World text steers Claude (review A, "the page commands, Claude obeys") | `WorldText` quoting is unchanged. Claude Code has no tools at all; its only output is the `{say, calls}` answer, and every call still goes through ToolSchemas, Guardrails and CautionPolicy in the mod |
| Claude Code's own powers (B1, B6) | Kept from `ClaudeCodeBrain`: `--tools ""` (no Bash, Edit, Read, WebFetch), `--strict-mcp-config` with no servers, `--setting-sources ""`, `--disable-slash-commands`, a replaced system prompt, and a working folder outside any project. Never `--dangerously-skip-permissions`. Caveman is text in the prompt, not a loaded plugin (section 7) |
| Free-form model or flags (B13) | The model and both efforts come from validated config keys. Chat and model output never reach the command line |
| Local channels (pipe-token) | None. The brain talks to its own children over stdin and stdout; nothing listens on a socket or a port |
| Oversized output (B11) | A stdout line over 1 MB ends the session with a `CLI` error instead of being buffered without limit |
| Test hooks in release (B9) | A fake `claude` is only ever reached through `claude_code_path`, which the player sets. Short idle timers work only with `-Dclaudeplayer.test=true` |
| Orphan processes (B10) | `ClaudeCodeEnv` records child pids in the working folder and kills the recorded trees when the server starts, after checking the image name is `claude`. A stopped order, a timeout and server shutdown kill the whole tree (`ProcessHandle.descendants()` first), not only the parent |
| Credentials and logs | The mod never reads `~/.claude` credentials. The child's environment drops `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` and `CLAUDE_CODE_USE_*`, so it always uses the subscription login. INFO logs carry ids, stop reasons, tokens and results only, never prompts |
| Two accounts (U2) | `/cp status` shows the Claude Code account email from `claude auth status`, when available |

## 10. Config (`claudeplayer-common.toml`, new keys)

Already on dev: `brain` (`claude_code`), `claude_code_path` (empty), `claude_code_timeout_seconds` (90), and `model` (`claude-haiku-5-5`, shared by both brains). Piece 1 added `reflexes` (true). New in pieces 3 and 4:

| Key | Default | Meaning |
|---|---|---|
| `claude_effort` / `claude_think_effort` | `high` / `max` | Claude Code's effort, normal and think-harder. The API brain keeps `effort` (`low`) |
| `think_harder_phrases` | `["think harder", "think hard"]` | Trigger phrases |
| `think_harder_timeout_seconds` | 30 | Offer timeout |
| `usage_warn_at` | `[0.80, 0.90]` | Warning thresholds |
| `caveman_at` | 0.80 | Caveman from here |
| `caveman_skill_path` | empty | Override for the skill file |
| `spare_process` | true | Keep one idle `claude` ready |
| `idle_stop_minutes` | 10 | Stop an idle session |

`hostileRange` (16) stays the radius, now measured on XZ for the reflex and the think-harder gate.

## 11. Spikes, before building pieces 3 and 4

Each one is a short script against the real CLI, with `ClaudeCodeBrain`'s own flags. The result goes into the modmap GAME FACTS (or a new CLAUDE CODE FACTS list).

| # | Question | If no |
|---|---|---|
| S1 | In one `--input-format stream-json --output-format stream-json --verbose` session with `--json-schema`, does every turn's `result` carry a `structured_output`? | The fallback in section 5 step 6: one process per call with `--resume` |
| S2 | Does a spare process started that way sit idle until its first line, and answer as fast as a warm one? | Start on order; keep only the idle stop |
| S3 | Baseline: what do `cache_read` and `input` look like across one order's calls today? (`ClaudeCodeBrain` logs them at INFO.) And in an S1 session? | No fallback: this measures the gain, and goes into the handoff |
| S4 | Can effort change for one turn in a running session? | `--resume <session> --effort max` respawn |
| S5 | Is `rate_limit_event` emitted on every turn of a stream-json session, and never in `--output-format json`? | If json carries it too, the fallback may keep json |
| S6 | On Windows, does the native `claude.exe` start child processes that outlive `destroyForcibly`? | If not, the tree kill is cheap insurance; keep it |

## 12. Testing

- **Pure core (JUnit, as today):** priority ordering, preempt, scrap and pause; `prio` clamping; reflex verdicts for each caution level, the lit creeper and hazards; XZ distance; Blockman block rendering and `WorldText` in it; think-harder gate, timeout and y/n intake; ledger thresholds, once-per-window and re-arm; the transcript-tail diff and the prefix check.
- **Sessions (JUnit, test flag on):** a fake `claude` (a small Java main behind a script that `claude_code_path` points to) replays recorded stream-json from the spikes. Tests cover:
  - only the new turn is sent, and a prefix mismatch restarts the session;
  - the spare handoff and the spare rebuilt on a prompt or effort change;
  - idle stop, `reset()`, the tree kill and the orphan sweep;
  - environment scrubbing (the fake echoes its environment);
  - the 1 MB line cap;
  - the fallback mode's `--resume` flags.
  - `ClaudeCodeBrainTest` keeps its parse and command tests.
- **Ledger (JUnit):** parsing `rate_limit_event` and `result.usage` from recorded lines, the 80% and 90% warnings once per window, re-arm after `resetsAt`, caveman on and off, and the file round trip.
- **`ToolParseTest`:** a case for `flee` and for `prio` on every tool.
- **PurityTest** still guards `core/`.
- **In game (F):** `claude: come here` with no key set, then a creeper approach (flee starts before Claude's next reply), lava in a path, the usage line in `/cp status`, and think-harder with and without a mob near.

## 13. Build outline (four pieces, in this order)

1. **Priority list and reflexes.** `ActionQueue` priorities, `Flee` and the `flee` tool, the `EscapePlanner` destination, `Reflexes`, the new `interrupt` rules, `prio` in ToolSchemas, the XZ distance. This works with today's API brain, so it lands and gets tested in game first.
2. **Blockman.** The prompt section and the pinned block in `PromptBuilder`, the `step` note. Both brains.
3. **Warm sessions** (builds on `ClaudeCodeBrain`). Spikes S1 to S3, S5 and S6 first.
   - **Hardening, landable alone:** `ClaudeCodeEnv` (the scrubbed environment, the pid record and orphan sweep, the tree kill) and `claude_effort`.
   - **Sessions:** `ClaudeCodeSessions` with stream-json in and out, sending only the new turn, the prefix check, the spare, idle stop, and `Brain.reset()` from `AgentLoop.abandon()`. If S1 fails, the `--resume` fallback instead.
4. **Usage, caveman and think-harder.** Spike S4 first. Then `UsageLedger` (fed by piece 3's stream-json output), the warnings, caveman from the installed SKILL.md, the think-harder offer and the effort switch, and `/cp status` lines.

Each piece has its own plan and gate: `./gradlew build` green, then F's in-game checks for that piece. Piece 2 (Blockman) can land before or after piece 3; they touch different files except `PromptBuilder`, which only Blockman changes.

**Confirmed by F (22 Oct):** `brain` defaults to `claude_code`; the 16-block XZ radius has no vertical cap (a mob deep in a cave below counts); the bridge is Java, not Node. **Confirmed with this revision (22 Oct):** the bridge lives inside the mod (no helper process, no MCP), and caveman is the installed skill's text in the prompt (no plugin loading).

## 14. Not in this work

Hosting for other machines, a Node bridge, Claude Code plugins for development (plan Phase 6), and API-brain streaming. Dropped in the 22 Oct revision:
- the separate bridge process and its socket;
- the MCP tool server and held calls;
- the jar hash check;
- dispatching tool calls as they stream (the calls arrive as one structured answer; reflexes cover danger).

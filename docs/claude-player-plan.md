# Claude Player: base code and execution plan

Planned 22 Oct 2026 by Haiku 5.5 (Max), as the planning pass. The rules, layout, systems, keys and tools are in `claude-player-modmap.md`. State and next steps are in `claude-player-handoff.md`.

## 1. What it is
A Forge 1.20.1 mod that adds a fake player named Claude to the world. Players on an allowlist type `claude: <order>` in chat. The mod sends the order and a fresh snapshot of the world to Claude. Claude replies with tool calls, the mod runs them at 3 per second, and Claude reports back in chat. The caution level decides when Claude fights, and the code enforces it.

Not in v1: the Vampirism addon, hosting for other players, distribution, auto-updates, and Claude Code as the brain.

## 2. Decisions

### Stated by F (locked)
- Forge 1.20.1.
- Reads chat and does what F says.
- Acts 3 times per second, based on updated information.
- Caution levels 0 to 5, with the names and meanings in the modmap (SYSTEMS 6).
- A separate mod from the Vampirism addon, which comes later.
- Planning on Haiku 5.5 Max. The skeleton is built on Opus.
- Possibly a Claude Code plugin. Undecided; Phase 6.

### Proposed, waiting on F
Answer each one. "Yes to all" is fine.

| ID | Decision | Proposed | Why |
|---|---|---|---|
| D1 | Brain backend | Messages API with a key. Needs API billing set up. | Billed apart from the subscription. The Claude Code route draws on the weekly limit you're protecting. |
| D2 | Players | Never attacked. Orders only from the allowlist. | Avoids accidental PvP and abuse through chat. |
| D3 | Level 5 when hit | Never swings. Flees, calls for help below 30% HP, accepts death. Respawn only by command. | Matches your definition of level 5. |
| D4 | Work area | Break and place only within 32 blocks of home. | Keeps the test world safe. |
| D5 | Planning style | Claude plans in big steps: a tool like mine_area runs the routine over many ticks. Claude replans on events. | Far fewer model calls, so cheaper. |
| D6 | First test world | Single-player first, then LAN. | Simplest to debug. |
| D7 | Git and commit trailers | Local repo on your PC. Trailers: your choice. | Claudefox says no trailers; Country of the Future says Co-Authored-By. The rules conflict. |
| D8 | Name and package | Claude Player, mod id `claudeplayer`, package renamed at setup. | Placeholder for now. |

## 3. Architecture

### Flow
1. A player sends `claude: <order>`. ChatIntake checks the allowlist and the trigger.
2. The server thread builds a snapshot. The brain thread gets an immutable copy.
3. AgentLoop sends the order, the snapshot, recent chat, and the tool schemas to Claude.
4. Claude replies with up to `batch_size` tool calls. They become one queued batch.
5. ActionQueue starts actions at 3 per second on the server thread. The executor checks Guardrails and CautionPolicy first.
6. When the batch is nearly done, a threat appears, or the order ends, the loop sends the results and a new snapshot back. Interrupts cancel the queue first.
7. A reply with no tool calls goes to chat. The order is done.

### Why 3 per second does not mean 3 model calls per second
At 20 ticks per second, 3 actions per second is one action every 6.67 ticks. Claude is not asked about each action. A batch of 12 covers 4 seconds. The next results message goes out when 4 of the batch's actions are still waiting, about 2.7 seconds in, so the reply usually arrives before the queue runs dry. If it doesn't, the agent waits. It never acts on a guess.

A busy agent makes about 15 calls a minute. An idle one makes none. The levers are batch size, the prefetch point, the compact snapshot, big-step tools (D5), and the call rate cap. If the Messages API's prompt caching applies to the fixed system prompt and tool list, use it (check the docs).

### Pacing, as code
```java
// server thread, once per tick
credit = Math.min(1.0, credit + actionsPerSecond / 20.0);
while (credit >= 1.0 && queue.canStartNext()) {
    queue.startNext();
    credit -= 1.0;
}
```

### Why the policy lives in code
A prompt can be ignored or argued with. The executor checks each attack against the caution table before running it. A denial goes back to Claude with the reason, so it learns the rule instead of retrying blindly.

### Why this shape
Pure logic (policy, pacing, parsing, sanitizing) is kept apart from game objects, so most of it can be unit-tested without Minecraft.

## 4. Brain backend (D1)

| Option | How | Cost | Limits | Fit |
|---|---|---|---|---|
| A. Messages API | Key from an env var. HTTP from a background thread. | Billed per token, apart from your subscription. Set a spend limit in the Console. | API rate limits | v1, proposed |
| B. Claude Code | A local helper runs `claude -p`, as Claudefox 0.11 plans it: one process per chat, stream-json input. | Uses your subscription. | The weekly limit you're trying to protect. | Later, behind the `Brain` interface |

Claudefox's helper runs Claude Code under its own sign-in. Its 0.11 plan adds a warning for when the claude.ai and Claude Code accounts differ. That only matters if you pick B.

## 5. Base code contracts

The modmap lists the files. These are the contracts between them, so each job can be built on its own.

```java
public sealed interface Action permits MoveTo, Say, Wait, Stop {}  // v2 adds Follow, Attack, Mine, Place, Equip, Craft
public record MoveTo(double x, double y, double z) implements Action {}
public record Say(String text) implements Action {}
public record Wait(double seconds) implements Action {}
public record Stop() implements Action {}
```

```java
public interface Brain {
    Reply send(Request request) throws BrainException; // background thread only
}
```

- `Request`: the order or updates, the snapshot, recent chat, tool schemas, and the step index.
- `Reply`: tool calls, optional text, stop reason, and token counts.
- `StateSnapshot`: immutable record; fields as in modmap SYSTEMS 3.
- `ActionResult`: a code string: `ok`, `denied:<reason>`, `failed:<reason>`, `cancelled`, or `queued`.
- `Guardrails`: `allowCall()`, `recordTokens(int)`, `allowBlockChange(BlockPos)`, `halt()`, `resume()`.
- `CautionPolicy`: `Verdict check(Level, Situation)`. Pure, with no game imports.

## 6. Phases

| Phase | Scope | Built by | Gate (done when) |
|---|---|---|---|
| 0. Setup and spikes | 1.20.1 MDK builds and `runClient` works. Spike A: `/cp spawn` creates a fake player, and a canned move moves it 5 blocks; log the position before and after. Spike B: one background HTTP call with a bad key; expect a 401, and the server keeps ticking. | F runs it; Opus guides | G0: both spikes work. The modmap GAME FACTS list is updated with what they proved. |
| 1. Skeleton | All LAYOUT files, with stubs where needed. Config. Chat intake. Body. Basic snapshot. Queue at 3/s. Executor for say, wait, stop, move_to. A stub brain: `claude: demo` runs a canned list. Tests: pacing, allowlist and trigger, cancel. | Opus 5.5 | G1: the demo runs at 3 actions per second. `claude: stop` halts within a second. Strangers' chat is ignored. Build is green. |
| 2. Brain | ApiBrain, PromptBuilder, ToolSchemas v1, AgentLoop, Guardrails (call rate, token cap, step cap), error handling, `/cp keycheck`. | Opus 5.5 for the core. Haiku 5.5 Max for ToolSchemas and parse tests. | G2: `claude: say hi` gets a real reply. `claude: come to me` runs move_to through a tool call. A bad key gives a clear chat error. The step cap ends a runaway loop. |
| 3. Movement and senses | Navigator with timeout. follow_player. Richer get_state. Result codes. | Haiku 5.5 Max, one behavior per job. Sonnet 5.5 for Navigator. | G3: move_to reaches a point within 20 blocks on flat ground in 10 s. An unreachable target gives `failed:no_path`. follow stays within 4 blocks for 30 s. |
| 4. Combat and caution | CautionPolicy, EscapePlanner, DamageWatcher, attack_entity behind the policy. | Sonnet 5.5 for policy edge cases and escape. Haiku 5.5 Max for the truth table and the watcher. | G4: truth-table tests pass for all six levels. In-world, a zombie approach and skeleton fire match the table. Level 5 never swings. |
| 5. Safety and polish | WorldText, break radius, death and respawn, `/cp status`, log cleanup, config docs, README. | Haiku 5.5 Max. Opus reviews. | G5: a 30-minute single-player soak. The token cap stops calls. `/cp stop` halts everything. TPS holds steady (`/forge tps`). |
| 6. Optional | A Claude Code plugin for development (build, run, read logs). A skin for the body (64×64 in Aseprite, once a skin source is confirmed). Option B brain. Composite tools such as mine_area. | Decide after G5 | Each item has its own gate. |

## 7. How to execute

- **Roles.** Opus 5.5 plans, writes job briefs, reviews, and runs every gate. Haiku 5.5 Max writes most of the code. Sonnet 5.5 takes the hard parts: navigation, policy edge cases, threading. This is the split the Claudefox run used.
- **Briefs quote, don't search.** Each brief names the exact file paths, signatures, and the design lines the job needs. In the Claudefox run, Haiku ran out of context on any job that had to read much code. Briefs that quoted exact files and lines worked.
- **Small jobs.** One job, one file or one behavior. Keep files under about 600 lines.
- **Tests and code in separate jobs.** One job writes the test from the spec. A later job writes the code that passes it.
- **Device writes.** If the work runs in the Cowork VM, keep each file write under 6 KB and write in pieces. That is the Claudefox rule.
- **Gates.** `./gradlew build` must pass, tests included. Then F runs the in-world checks. Claude can't launch Minecraft, so F runs `runClient` and pastes the relevant `latest.log` lines with what happened in game.
- **Progress.** Report each job in AI Tracker, as the Claudefox run did.
- **Budget.** Opus is used for planning, the skeleton, and gate reviews only. That protects your weekly limit. Most code comes from Haiku 5.5 Max.
- **Commits.** After each gate, under the rule chosen in D7.

## 8. Tests

- **Unit (JUnit 5, no Minecraft running):**
  - CautionPolicy truth table: six levels × (hostile adjacent, hostile far, player target, low HP, escape exists, hurt while fleeing).
  - ActionQueue: 3 starts per 20 ticks; no burst after a 100-tick stall; cancel empties the queue.
  - ChatIntake: allowlist, trigger, `claude: stop`.
  - WorldText: quoting, length caps, text that imitates instructions.
  - Guardrails: call rate, token cap, step cap, break radius.
  - ToolParse: valid and malformed tool_use blocks.
- **In-world (F):** one checklist per gate, from the phase table. Record results in the handoff.

## 9. Risks

| Risk | Mitigation |
|---|---|
| API cost climbs | Event-driven calls, batch size, prefetch point, compact snapshot, call-rate and token caps, big-step tools |
| A network call freezes the server | Background thread only, timeouts, checked at every gate |
| Prompt injection through world text | WorldText quoting, policy in code, orders only from the allowlist |
| FakePlayer behaves oddly in 1.20.1 | Spike A in Phase 0, fixed before Phase 1 |
| Pathfinding gets stuck | Timeouts, `failed:no_path`, and Claude is told the reason |
| The body wanders into unloaded chunks | Keep it near loaded chunks; check in Phase 3 |
| The agent griefs the world | Break radius, no player attacks, allowlist |
| Death loops | Stop on death; respawn only by command |
| The model acts on stale state | Snapshot refresh each second; if the reply is late, wait rather than guess |
| Threat interrupts flood the API | Debounce: one per threat, at most one every 5 s |

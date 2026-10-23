# Claude Player: handoff

Updated 22 Oct 2026 after the skeleton pass (Opus 5.5). Read this first, then the modmap RULES, then plan §6 and §7.

## Where things stand
- Every modmap LAYOUT file exists. `./gradlew build` is green: 85 unit tests on the pure core, and the jar lands in `build/libs/claudeplayer-0.1.0.jar`.
- Built: the harness (queue at 3/s, executor, body, snapshot, navigator, damage watcher, `/cp`), the rules (caution policy, guardrails, world text, interrupt debounce), the brain (Messages API client, prompts, tool schemas, agent loop with pipelining and interrupts, demo brain), and short- and long-term memory.
- Nothing has run in Minecraft yet. Lines marked COMPILED in the modmap GAME FACTS compile against Forge; none are confirmed in game.

## Decisions
- Stated by F (locked): plan §2.
- D1 to D8: the skeleton follows every proposed default. That means the Messages API with a key; players never attacked and orders only from the allowlist; level 5 never swings; a 32-block work area; big-step planning; single-player first; mod id `claudeplayer`, package `com.example.claudeplayer`. F confirms or overrides each one here.
- Git (D7): https://github.com/F-F-Bukharin/Claude-In-Craft, branch `main`, MIT license (`LICENSE` and `mod_license`). Commits carry a `Co-Authored-By` trailer until F decides otherwise.
- Made during the build, for F to review:
  - The body is a server-driven `ServerPlayer` (`AgentPlayer`), not Forge's `FakePlayer`, which doesn't tick, can't be hurt and never dies. It counts as a real player (tab list, sleep vote).
  - Pure logic lives under `core/`, and PurityTest enforces it. The LAYOUT moved to match.
  - `max_tokens` is 4096 instead of 1024, because Haiku 5.5 thinks by default and thinking counts against it. `effort` is low.
  - Pacing keeps the leftover credit. The planned formula gives 2.86 actions per second, not 3.
  - `tool_phase` (default 2) exposes tools as gates pass. Follow and attack code already exists behind it.
  - `idle_reactions` is on: a threat while no order runs starts one reaction call.
  - Memory, added at F's request: short-term notes (in RAM, fading) and long-term memories (`<world>/claudeplayer/memory.json`), plus the `inspect_container` tool.
  - Extra commands: `/cp memory`, `/cp allow`, `/cp deny`. `claude: demo` runs the G1 canned list without the API.
  - Option B is built, at F's request: `brain = "claude_code"` (the default) runs F's `claude -p` on their subscription. It runs with `--tools ""`, `--setting-sources ""`, `--strict-mcp-config`, a replaced system prompt (file), and a `--json-schema` for `{say, calls}`. `--bare` is avoided because it ignores the subscription login. It runs in `%TEMP%/claudeplayer-claude-code`, so no CLAUDE.md is loaded. A real two-turn order took 3 to 4 s per turn. `brain = "api"` restores the Messages API.
  - The game ran in a CurseForge instance ("Claude-In-Craft 1.20.1"). Quickstart, the demo, stop, keycheck, death and respawn all worked.

## Still to do, in order
1. F answers D1 to D8 ("yes to all" is fine).
2. F runs `./gradlew runClient` and opens a single-player world with cheats on. Once a world named "Claude Test" exists, `./gradlew runClient -Pquickstart="Claude Test"` does steps 2 and 3 in one go.
3. Phase 0 and G1 without a key: `/cp quickstart` (it allows you, spawns the body and runs `claude: demo`). The body should walk a 3-block square at about 3 actions per second, and `claude: stop` should halt it within a second. Check that the body is visible, falls, takes damage, dies, and that `claude: respawn` works. Paste the relevant `latest.log` lines here.
4. Spike B: `/cp keycheck` with no key, then a bad key, then the real one (env var `ANTHROPIC_API_KEY`, or `run/config/claudeplayer.key` in dev). Expect clear messages, and the server keeps ticking.
5. G2: `claude: say hi`, then `claude: come here`. Mark the modmap GAME FACTS IN-GAME, or correct them.
6. Phases 3 to 5 as the plan says. Raise `tool_phase` to 3 after G3 and to 4 after G4.
7. Piece 1 (priority list and reflexes) in game: `./gradlew runClient -Pquickstart="Claude Test"`, and paste the `reflex` log lines here.
   - M1. At caution level 4 or 5, spawn a zombie 10 blocks away. The body starts fleeing before Claude's chat reply, and the log shows `reflex FLEE`.
   - M2. At level 0, ignite a creeper near the body (flint and steel). It flees.
   - M3. Start a long `claude: walk to <x y z>`, then pour lava onto the path ahead of the body (vanilla paths route around existing lava). The body halts at the edge, and the move reports `failed:hazard_lava`.
   - M4. Give an order with routine steps, then spawn a zombie. The routine steps come back `scrapped`, and a prio-2 move resumes afterwards.
   - M5. `claude: stop` while it flees. It halts within a second.
   - Known: a hostile in a cave far below still counts (XZ distance), so the body may take a short flee. The 30 s re-arm per mob bounds it.
   - Watch in M2: a creeper whose fuse flickers (lost sight, or the body at the 3 to 7 block edge) now triggers a flee on each re-light.
8. Piece 1 follow-ups, from the final review. None blocks the in-game checks.
   - The model never hears a reflex's own outcome (`failed:no_escape`, `ok:no_threats`, `hazard_*`, a denied swing). Add a note when a `reflex-` entry ends in an error.
   - `abandon()` spares every level-3 entry, including the model's prio-3 calls for a dropped order; a leftover prio-3 `stop` would cancel the next order's batch. Spare only `reflex-` entries.
   - A second reflex during one model call overwrites the first in `interruptInFlight`. Append instead.
   - `follow_player` has no hazard check. Share MoveHandle's probe.
   - The prompt should say to give an approach and its attack the same prio, since higher levels run first.
   - A slab, stair or carpet walkway over a drop can read as `hazard_drop` at low HP.
   - A hit from beyond `hostile_range` (a ghast) builds a flee that scraps routine work, then returns `ok:no_threats`.
   - `"prio": null` fails the call; it could count as absent.
9. Claude Brain screen, in game (merged into dev). Right-click the body.
   - B1. As an allowlisted player or an op, right-click Claude. A chest opens, titled `Claude Brain: order from <you>: "..."`. The running action comes first (lime dye), then the waiting ones (paper), each named by its tool and with its priority.
   - B2. As anyone else, right-click Claude. Nothing happens.
   - B3. While a level 3 flee runs, open the chest. The flee shows as running, and any paused level 2 action shows as paused (clock, "resumes after a reflex"). Clicks in the chest move nothing.
   - Watch in B1: the title can run past the chest's edge; the order text is cut at 30 characters.
10. Map view and short chat (23 Oct, F reported long chat and a blind Claude). Every world_state now carries `map` (17 by 17, at feet level) and `map_origin`. The `look` tool gives up to 49 by 49. Chat is capped at two lines of 100 characters in code, and the prompt asks for 15 words at most.
   - V1. `claude: what do you see?` Its answer should match the terrain around it: trees, water, walls.
   - V2. Build a wall of 2 blocks with one gap, stand behind it, and say `claude: come here`. It should route through the gap.
   - V3. Ask for something wordy (`claude: tell me about yourself`). Chat shows at most two lines.
   - Trigger (23 Oct, F's choice): the default is now `C`, case-sensitive and followed by a space, ':' or ','. `Claude:` (the body name plus a colon) always works too. Existing config files keep their saved `trigger` until it is edited.
   - Not done: the Blockman status block pinned to each turn (spec section 7), and the flee issues, which wait on F's description of how flees failed.

11. Skills, autonomy and the faster helper (23 Oct, merged into dev; 196 unit tests green; nothing tried in game yet). F's choices: natural blocks anywhere and man-made only if Claude placed them; autonomy calls the model only between goals, at most once a minute; after an order, autonomy resumes.
   - In game, try: `C mine the log in front of you`; `C get 10 logs`; `C craft planks and sticks`, then a wooden pickaxe away from and next to a crafting table; with raw iron and coal next to a furnace, `C smelt the iron`, then `take` the ingots; `C dig down 15` (it stops before lava and reports ores seen); `C put your logs in the chest`; let it get hungry with bread in its inventory; `C follow me`; `C go do your thing, I'll call you` (about one `autonomy goal #n` log line a minute), then `C come here` (it should resume afterwards), then `C stop`.
   - Helper: warm sessions are merged. Measured with the real prompt: opening turns 1.7 to 4.9 s, follow-up turns about 2 to 3.4 s, against 7.7 s cold before. Most of what's left is Haiku's thinking, which this CLI can't turn off (`MAX_THINKING_TOKENS`, `alwaysThinkingEnabled` and `CLAUDE_CODE_DISABLE_THINKING` were all tried).
   - Fixed: Haiku called game actions as native tools on 4 of 4 opening turns, which wasted a round trip and often ended in "I can't move". The rendered prompt now says StructuredOutput is the only tool and lists actions compactly. Measured afterwards: 0 of 24.
   - **Known bridge bugs, open**, from a fresh review of the warm sessions (verdict: ship after fixes). Fix 1 and 2 before relying on it:
     1. The turn timeout doesn't cover the stdin write: a cold child stalled before reading stdin can hang the brain thread. `/cp stop` or a new order kills it.
     2. A `reset()` during an opening's start is lost, so `/cp stop` right after an order doesn't stop that model call.
     3. The leftover-process sweep doesn't match Claude on Linux or macOS (symlinked paths).
     4. `reset()` does a process walk and pid-file I/O on the server thread.
     5. The idle sweep can race a turn's start.
     6. FIXED 23 Oct: the scrub now also removes `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`, `CLAUDE_CODE_USE_FOUNDRY` and `AWS_BEARER_TOKEN_BEDROCK` (test `scrubRemovesTheCloudProviderSwitches`).
     - Branch `fix/warm-sessions` (commit 3328d70) holds an unfinished, untested start on these. It was stopped to save usage; check it before reusing it.
   - The system prompt now points to gather and dig for anything beyond a block or two (23 Oct).
   - Next latency win: let a reply mark the order done, so a simple order ("come here", "say hi") skips the report turn. That's one model call instead of two.

## Claude Code facts (spikes from spec section 11, run 23 Oct with claude.exe 2.1.299, Haiku 5.5, effort low)
- S1, yes: in one `--input-format stream-json --output-format stream-json --verbose --json-schema` session, every turn's `result` carries `structured_output`. The cache works across turns: cache_read went 1615, then 1725, then 2033.
- S2, yes: a spare started on stream-json input sits idle with no output until its first line. Its first turn took 4.1 s, against 7.7 s for a cold start. Warm later turns took 2.3 to 4.3 s, depending mostly on output length.
- S5, partly: `rate_limit_event` came only on a session's first turn, not on every turn. It carries `utilization` and `resetsAt` per window, and `status` (`allowed_warning` past `surpassedThreshold` 0.75). The ledger gets one reading per session unless it asks again.
- S6, no: an idle `claude.exe` (no tools) starts no child processes, so `destroyForcibly` is enough and the tree kill is cheap insurance.
- Seen in passing: F's seven-day window read 0.77 on 23 Oct.
- Seen in passing: with a system prompt that doesn't explain the JSON reply, Haiku tried a native tool call ("No such tool available: say") before answering. `ClaudeCodeBrain`'s HOW_TO_ACT section prevents that; keep it.

## Risks to watch
- API cost during busy play (plan §3 and §9). Watch the token count in `/cp status`.
- Spike A is the biggest unknown: the fake connection, server-side physics, and path following.
- The body counts toward the sleep vote. In single-player, use `/gamerule playersSleepingPercentage 50` until that's handled.
- Prefetch counts starts, so a batch heavy on moves may plan its next step early. Tune `prefetch_at` if it does.
- The project lives in OneDrive, so `build/`, `.gradle/` and `run/` get synced. Consider excluding them, or moving the project out.

## How to pick up
- Start with the modmap RULES, then the next open gate in plan §6.
- To add a tool: a record in `core/act/Action.java`, an entry in `core/brain/ToolSchemas.java`, a branch in `body/ActionExecutor.java` (or a core runner like `MemoryActions` for pure tools), and a ToolParseTest case.
- Team: Opus 5.5 plans and reviews. Haiku 5.5 Max writes. Sonnet 5.5 takes the hard jobs. Briefs quote exact files.

## Gate log

| Gate | Status | Notes |
|---|---|---|
| G0 spikes | ready to run | Spike A: `/cp spawn` + `claude: demo`. Spike B: `/cp keycheck` |
| G1 skeleton | build green | unit tests pass; in-world checks open |
| G2 brain | build green | in-world checks open |
| G3 movement | code in, gated | `tool_phase` 3 |
| G4 caution | code in, gated | truth-table tests pass; `tool_phase` 4 |
| G5 soak | open | |

## Files
- `docs/claude-player-plan.md`: decisions, architecture, contracts, phases, execution, tests, risks. From the planning pass; the modmap records where the build differs.
- `docs/claude-player-modmap.md`: rules, layout, systems, keys, tools, facts, status.
- `docs/claude-player-handoff.md`: this file.
- `README.md`: setup and use. `CLAUDE.md`: rules for agents working in this repo.

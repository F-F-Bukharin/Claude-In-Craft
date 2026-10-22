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

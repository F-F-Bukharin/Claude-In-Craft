# Claude Player: handoff

The one handoff. Updated 24 Oct 2026 (Opus 5.5), after the fix round and the standalone helper. Read this first, then `CLAUDE.md`, then the modmap RULES.

## Where things stand
- **GitHub:** `dev` is pushed (24 Oct) and matches `origin/dev`. `main` is still at `1669171` until F says to fast-forward it.
- **The 24 Oct round, merged into `dev`:**
  - `fix/small-fixes`: ender chest refusal in `store` and `take`; `lava_near_player` is `denied:`; `use_bucket` takes an optional `fluid`; auto-eat counts hostiles within 16 blocks straight-line; a test pins a busy snapshot's JSON size.
  - `fix/reflex-followups`: the chase-heard remainder (F1 to F4) and every piece 1 follow-up (section below).
  - `fix/bridge-bugs`: warm-session bugs 1, 2, 4 and 5.
  - `feat/claude-code-helper`: the standalone helper in `helper/` (section below).
- **`dev` also holds, from 23 Oct:**
  - level gaze while walking, and crops and farmland on the map (`fix/gaze-and-crops`);
  - the Brain panel as a live text screen (`feat/claude-brain-text-gui`);
  - `till`, `plant`, `harvest` and `use_bucket` (`feat/farming-buckets`);
  - text vision, the `see` tool (`feat/text-vision`);
  - the combat routine, `fight` with auto-gear (`feat/combat-routine`);
  - glance part 1, the model side (`feat/glance-model-side`). The executor still answers `failed:no_client` until part 2.
- `./gradlew build` is green with 282 tests; `./gradlew -p helper build` is green with 57 (24 Oct).
- F's flow: pull `dev`, push `dev`, fast-forward `main` on F's word.
- **Account:** F is on the Max plan (23 Oct).
- **Gradle needs Java 17:** `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"`. The PATH Java is a 32-bit Java 8 that breaks Gradle.
- **F's test profile:** `C:\Users\Huntress\curseforge\minecraft\Instances\Claude-In-Craft`.
  - Its jar is from `1669171`, not current `dev`.
  - Its config has `trigger = "C"` and `tool_phase = 4` (attacks on).
  - Install by copying `build/libs/claudeplayer-0.1.0.jar` into its `mods` folder, only while the game is closed (Windows locks the jar).
- **The game log** to read after F plays: `<profile>\logs\latest.log`. Look for `reflex` lines and `claude code reply ... tokens` lines.

## Work in flight
Nothing. The 24 Oct builders' worktrees under `.claude/worktrees/` can be removed (`git worktree remove`); their branches are merged. Their reports are in session `02761c29`'s scratchpad: `C:\Users\Huntress\AppData\Local\Temp\claude\C--Users-Huntress--claude-spares-spare-ZmkL3R\02761c29-3a11-4d52-9219-e82b88985d0a\scratchpad\` (`small-fixes-report.md`, `reflex-followups-report.md`, `bridge-bugs-report.md`, `helper-report.md`).

`feat/glance-vision` (at `78c0e13`) holds no code: its builder stopped while exploring. Its findings are in `glance-vision-report.md` in the 23 Oct session's scratchpad (`...\26f10b8d-daec-48fa-b2ce-a6b4c1252d3c\scratchpad\`).

## Next, in order
1. **Glance part 2, the renderer.**
   - Plugs in at `ActionExecutor` (the `Glance` branch): return `ActionResult.picture(png)` in place of `failed:no_client`, sized by `settings().visionWidth()`/`visionHeight()`. AgentLoop already admits at most one glance per turn and applies the cooldown (`VisionGate`), so the executor makes no gate calls.
   - A client-only class behind `FMLEnvironment.dist == Dist.CLIENT`.
   - The server thread asks the client thread (`Minecraft.getInstance().execute`) to render the level from the body's eyes (the client entity with the same network id) into an off-screen `TextureTarget` of `vision_width`×`vision_height`, with no HUD and no hand.
   - Read it back to a PNG, restore the camera entity, the main target and the viewport, and hand the bytes back with `server.tell`. A 2 s `failed:render_timeout`; `failed:no_client` on a dedicated server; INFO log `glance WxH NB`.
   - Also `/cp glance` (`isAdmin`), which saves `<game dir>/claudeplayer/glance-<time>.png` so F can check what Claude sees. It skips the gate.
   - There are no decompiled sources in the Gradle cache; check signatures with `javap` on the mapped Forge jar: `~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.28_mapped_official_1.20.1/forge-1.20.1-47.4.28_mapped_official_1.20.1.jar`.
   - If a clean off-screen render isn't possible in 1.20.1, say so; don't ship something half-working.
   - Proven: Claude Code with the brain's lockdown flags takes a base64 PNG image block over stream-json mid-session, and Haiku 5.5 reads it. Image tokens are about w×h/750, so 320×180 is about 80 tokens.
   - Part 1 decisions for F to review: the cooldown starts at admission, so a failed glance still costs 15 s; a picture reported late forces one more model round.
2. **Install and playtest:** the in-game checks below, plus farming, buckets, fight, `see` and `glance`.
3. **A code-structure review on Fable** (F's idea, 24 Oct): a fresh reviewer that reads the code without the builders' framing, now that this round is in.

## Ideas: what we could and should do

**F's top asks (23 Oct):**
- **Bigger vision range.** F: "24 blocks is miniscule, he didnt see a tree on top of a hill."
  - Raise the map and `look` range to about 64, sampled coarser with distance, for example 1 block per cell near and 4 per cell far.
  - Make it height-aware: mark the top block of each column, so trees and hills above Claude's feet level show up (today the map is a slice at foot level, crops excepted).
  - Raise `see`'s ray length from 32 to about 96. Rays are cheap, but keep the rule that rays stop at unloaded chunks.
  - Add a landmarks line to `see`/`look`: the highest point, the nearest trees, water, and structures, each with direction and distance.
- **A `roam` skill, next to exploring.** `roam(radius, minutes)`: wander around the start point, picking waypoints toward directions not seen yet. Note what it finds (trees, ores, water, villages, animals) as short-term notes with positions, then walk back to where it started and report once. It's a routine like gather and dig, at level 1, so danger scraps it.

**Should do:**
- **Usage warnings** (spec piece 4): read `rate_limit_event` (`five_hour` and `seven_day` utilization) from the warm session's stream-json; warn in chat at 80% and 90%; from 80% on, put caveman (the installed `caveman/SKILL.md` text) in the prompt; show it in `/cp status`. Less urgent for F on Max; it matters most for the friends package, where each friend plays on their own account.
- **Think-harder:** an offer of `(y)/(n)` to switch to `claude_think_effort = max`, never while a hostile is within 16 blocks horizontally (spec section 8).
- **Blockman** (spec piece 2, partly done by F's short chat): the pinned ORDER/STEP/POS/THREATS/QUEUE block at the end of each turn, so Claude doesn't lose track of the order.
- **The snapshot is over its target:** a busy scene measures 3108 bytes, against the 2 KB goal (`StateSnapshotTest` pins it at 3500). Decide whether to trim it (fewer inventory slots, shorter keys, players and hostiles capped lower) or raise the target.
- **Bridge leftovers** (from `bridge-bugs-report.md`):
  - a reset that lands before `send()` registers the call still lets that call run; AgentLoop drops its reply, so the cost is one wasted model call. Closing it needs a small AgentLoop change;
  - `killLeftovers()` in `ClaudeCodeBrain`'s constructor still walks processes on whatever thread builds the brain.
- **Switch the mod to the helper library**, so the bridge lives in one place (`helper/` and `core/brain/ClaudeCode*` are two copies now).
- **Helper usage measurement and guard:** log tokens per model next to `rate_limit_event`'s 5-hour and weekly readings, so tokens per percent of the plan can be measured; then warn at 80% and refuse requests past a limit the user sets.

**Could do:** sleep in a bed at night (the body counts toward the sleep vote); a `build` routine from a short block list; fishing; sorting chests; trading with villagers; taming; a `home` command so Claude goes back and stores loot.

**Later, once the major bugs are ironed out: a friends package**, so friends play on their own Claude Code accounts.
- A CurseForge profile export (1.20.1, Forge 47.4.28, the mod jar) that friends import.
- A setup `.bat` that finds Claude Code (PATH, `%USERPROFILE%\.local\bin`, npm global), installs it with the official installer only if the friend agrees, and starts its sign-in.
- A short START HERE: import, run the setup, open a world, `/cp quickstart`, `/cp keycheck`.
- Learn from Claudefox's `claude-in-zen/friends/` and its review notes U1 (don't present an unsigned build as normal), E6 (don't assume `.local\bin`) and U2 (show which account is signed in). Never ship F's own login or keys.
- Brainstorm it with F first. A standalone launcher app is possible, but a CurseForge export does the same job with far less to maintain.

## In-game checks
Paste the relevant `latest.log` lines here after each run. Orders start with `C` (F's trigger) or `Claude:`.

Early gates, done in F's CurseForge playtests (22 and 23 Oct): quickstart, the demo, stop, keycheck, death and respawn; a crafting table and a pickaxe from chat; mining and crafting end to end. Still open from them: `/cp keycheck` with no key and a bad key (API brain), and D1 to D8 (F answers; "yes to all" is fine).

1. **Playtest 1 fixes** (on dev).
   - R1. At level 4 or 5, a skeleton in a cave 20+ blocks below, or 17+ blocks away in a straight line on a hill, triggers no flee. A zombie 10 blocks away on flat ground still does (`reflex FLEE`).
   - R2. A zombie that keeps chasing after the first flee: the body flees again within about 2 s of the flee ending, before the zombie lands a hit, and the log shows a second `reflex FLEE` for the same `#id`, and no extra Claude chat line for the re-flee. The second flee leaves no new INTERRUPT in the transcript, and no reaction call while idle.
   - R3. At a HOLD level (level 3, zombie at 5 blocks, full HP), the zombie standing nearby causes no repeated interrupts (one `near:` interrupt only).
   - R4. `C what's in the ender chest` gets an explanation that ender chests are personal, not "tool not available".
   - R5. A long order runs past 32 calls without "Session token cap reached" when most input is cache reads.
   - P1. `C come here` walks to you.
   - P2. `C what's in the chest` names its contents without being told where it is.
   - P3. `C follow me` follows for up to 60 s.
2. **Reflexes** (piece 1).
   - M1. At caution level 4 or 5, spawn a zombie 10 blocks away. The body starts fleeing before Claude's chat reply, and the log shows `reflex FLEE`.
   - M2. At level 0, ignite a creeper near the body (flint and steel). It flees.
   - M3. Start a long `C walk to <x y z>`, then pour lava onto the path ahead of the body (vanilla paths route around existing lava). The body halts at the edge, and the move reports `failed:hazard_lava`.
   - M4. Give an order with routine steps, then spawn a zombie. The routine steps come back `scrapped`, and a prio-2 move resumes afterwards.
   - M5. `C stop` while it flees. It halts within a second.
   - Known: the reflex radius is 16 blocks in a straight line, so a hostile in a cave 10 blocks below still counts. The 30 s re-arm per mob bounds it.
   - Watch in M2: a creeper whose fuse flickers (lost sight, or the body at the 3 to 7 block edge) triggers a flee on each re-light.
3. **Combat** (`fight`, at `tool_phase` 4).
   - A zombie at each caution level 0 to 5: 0 chases up to 16, 2 holds within 6, 3 and 4 fight only adjacent with no way out, 5 never fights.
   - Gear swaps: a sword from the bag, a better chestplate, a shield into an empty off-hand.
   - A player beside a zombie is never hit.
   - Watch: at levels 3 and 4, a mob stepping back past 2 blocks ends the fight, and the chase rule may loop flee and fight. An elytra or carved pumpkin gets swapped for real armor.
4. **Skills, autonomy and the faster helper** (196 tests at merge; nothing tried in game yet). F's choices: natural blocks anywhere and man-made only if Claude placed them; autonomy calls the model only between goals, at most once a minute; after an order, autonomy resumes.
   - Try: `C mine the log in front of you`; `C get 10 logs`; `C craft planks and sticks`, then a wooden pickaxe away from and next to a crafting table; with raw iron and coal next to a furnace, `C smelt the iron`, then `take` the ingots; `C dig down 15` (it stops before lava and reports ores seen); `C put your logs in the chest`; let it get hungry with bread in its inventory; `C follow me`; `C go do your thing, I'll call you` (about one `autonomy goal #n` log line a minute), then `C come here` (it should resume afterwards), then `C stop`.
   - Farming and buckets: `till`, `plant`, `harvest` (replants), `use_bucket` (lava only in the work area, never within 4 blocks of a player).
   - Helper: warm sessions are merged. Measured with the real prompt: opening turns 1.7 to 4.9 s, follow-up turns about 2 to 3.4 s, against 7.7 s cold before. Most of what's left is Haiku's thinking, which this CLI can't turn off (`MAX_THINKING_TOKENS`, `alwaysThinkingEnabled` and `CLAUDE_CODE_DISABLE_THINKING` were all tried).
   - Fixed: Haiku called game actions as native tools on 4 of 4 opening turns. The rendered prompt now says StructuredOutput is the only tool and lists actions compactly. Measured afterwards: 0 of 24.
   - **Bridge bugs**, from a fresh review of the warm sessions. All fixed; none checked in game:
     1. FIXED 24 Oct: the turn timeout covers the stdin write (the prompt goes out from a writer thread), so a stalled cold child times out and is killed.
     2. FIXED 24 Oct: a reset at any point of a call (start, write, read) ends it as cancelled, so `/cp stop` right after an order stops that model call.
     3. FIXED 23 Oct (untested on Linux and macOS): `isClaude` also matches by command line (`--input-format stream-json` plus `--system-prompt-file`) when the path is a resolved symlink or node.
     4. FIXED 24 Oct: a reset on the server thread only flags the call; the process walk and pid-file I/O run on a reaper thread (measured under 0.3 ms on the caller).
     5. FIXED 24 Oct: a call claims its session under the sessions lock, so the idle sweep never stops it.
     6. FIXED 23 Oct: the scrub also removes `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`, `CLAUDE_CODE_USE_FOUNDRY` and `AWS_BEARER_TOKEN_BEDROCK`.
     - Watch: a stop during a session's start also kills the warm spare, so the next order starts cold (about 3.6 s).
   - The system prompt points to gather and dig for anything beyond a block or two. Autonomy says a failed goal call once, not every interval. Gather no longer stops with `inventory_full` when a partial stack of the target has room.
   - Next latency win: let a reply mark the order done, so a simple order ("come here", "say hi") skips the report turn. That's one model call instead of two.
5. **Map view and short chat.** Every world_state carries `map` (17 by 17, feet level, crops 2 below to 1 above) and `map_origin`; `look` gives up to 49 by 49. Chat is capped at two lines of 100 characters, and the prompt asks for 15 words at most.
   - V1. `C what do you see?` Its answer should match the terrain around it: trees, water, walls.
   - V2. Build a wall of 2 blocks with one gap, stand behind it, and say `C come here`. It should route through the gap.
   - V3. Ask for something wordy (`C tell me about yourself`). Chat shows at most two lines.
   - Existing config files keep their saved `trigger` until it is edited.
6. **Vision.** `see`: `C what's in front of you?` near ores, a chest, ripe wheat; the answer should name them with distances. `glance` waits on part 2; until then it answers `failed:no_client`, and the model should say so plainly.
7. **Brain panel.** Right-click the body.
   - B1. As an allowlisted player or an op: a small panel with the order header (`order from <you>: "..."`, wrapped), the running action, the waiting ones with their prio, and paused ones marked. `Nothing queued` when idle.
   - B2. As anyone else: nothing happens.
   - B3. While a level 3 flee runs, the panel shows the flee as running and any paused level 2 action as paused. It refreshes about once a second.
   - B4. Esc closes the panel, and the game keeps running behind it.
   - Watch in B1: a very long order makes a tall panel; check that it fits a small window.
8. **The 24 Oct reflex fixes.**
   - C1. Level 3, the body boxed in (no escape), a zombie walking in from 5 blocks. `fight` starts within about half a second of the zombie reaching 2 blocks (`reflex FIGHT`), not up to 2 s later.
   - C2. At a HOLD level, a zombie that stays 4 blocks away gives only the first `near:` interrupt and no `reflex` lines, until it closes in.
   - C3. Level 4, boxed in, a zombie arrives during an order. The flee fails, and the next turn carries `reflex flee: failed:no_escape`.
   - C4. At `tool_phase` 4: `C kill that zombie`, then `C come here` mid-fight. The fight stops at once and the body walks to you; nothing from the first order runs afterwards.
   - C5. `C follow me`, then walk along a lava pool, or step off a 20-block drop with the body at low HP. It halts at the edge, and the follow ends with `failed:hazard_lava` or `hazard_drop`.
   - C6. At about 4 HP, `C walk to` a point across a slab or carpet bridge over a deep ravine. It crosses without `hazard_drop`.
   - C7. In the Nether, a ghast hits from far off during a routine order. No `reflex FLEE`, the routine isn't scrapped, and the interrupt says "beyond 16 blocks".
   - C8. `/cp status` says "weighted tokens".
   - Watch TPS with several mobs near at a HOLD level: each recheck runs up to 8 path searches, now every half second per mob.
9. **The 24 Oct small fixes.**
   - `C put your logs in the ender chest` and `C take from the ender chest` get the "ender chests are personal" explanation.
   - With a water and a lava bucket, `C pour lava there` pours lava (`use_bucket` with `fluid`); near another player it is `denied:lava_near_player`.
   - Hungry, with bread, and a zombie in a cave 20 blocks below: the body eats (the hostile is out of straight-line range).

## Piece 1 follow-ups
Done on `fix/reflex-followups` and merged 24 Oct, with the chase-heard remainder (F1 to F4): a failed reflex is a note in the next turn; a dropped order spares only `reflex-` entries; several reflexes during one call all go out; `follow_player` uses move_to's hazard probe; the prompt says to give an approach and its attack the same prio; a slab, stair or carpet under the feet is floor for the probe; a hit from beyond `hostile_range` gets no reflex. In-game checks C1 to C8 above.

## The standalone Claude Code helper (`helper/`, 24 Oct)
For F's friend, who wants Claude without an API key and without the mod.
- A separate Gradle build, not part of the mod's: `./gradlew -p helper build` (57 tests). Package `com.example.claudecodehelper`; Gson is its only dependency, bundled into a fat jar.
- A plain Java library: warm Claude Code sessions on the user's own login, with text, images, an optional JSON schema, usage and the `rate_limit_event` reading.
- A server on 127.0.0.1:8787 shaped like the Messages API: `POST /v1/messages` (system, multi-turn, base64 PNG and JPEG, tools emulated with structured output, `stream: true` replayed as standard events), `GET /health`, an optional `--token`, a cap of 4 sessions (529 past it), and refusals for web pages and other Host names.
- The zip: `helper/build/distributions/claude-code-helper-0.1.0.zip` holds the jar, `start-helper.bat`, `start-helper.sh` and `HOW-IT-WORKS.txt` (how it works, six setup steps, SDK examples, troubleshooting, limits, safety, terms). A copy went to F as `build/helper/claude-code-helper-0.1.0.zip`.
- Smoke-tested against the real `claude` on Max: replies in 1.2 to 4.8 s, follow-ups reusing the warm session, tools, images, streaming, a wrong token refused. The Python and TypeScript SDK examples weren't run.
- For F to review: tool input schemas go in the system prompt rather than being enforced; sessions stay warm after `end_turn` until the 10-minute idle stop; `/health` needs no token.
- Frame (F, 24 Oct): one person, their own plan, on their own PC. Every request counts against that plan's limits.
- Measured on 24 Oct from Claude Code's transcripts: building and testing the mod (22 to 24 Oct) would have cost about $102 at API rates (Opus 5.5 $97). The in-game brain's 94 logged replies on Haiku 5.5 came to $0.04.

## Playtest 1 (22 Oct, CurseForge)

| # | Found | Status |
|---|---|---|
| 1 | Claude couldn't see player coordinates | Fixed on dev: players carry `pos` |
| 2 | No follow command | Fixed on dev (F): `follow_player` is at `tool_phase` 2 now, on by default |
| 3 | Long replies cut off at 256 characters | Fixed on dev (F): `ChatLines` splits a reply into at most two lines of 100 characters at a sentence end or space, and the prompt asks for 15 words |
| 4 | No mining, crafting or placing | Fixed on dev (F): `mine_block`, `place_block`, `equip`, `craft` and `smelt`, plus the `gather` and `dig` routines |
| 5 | Each order forgets the last conversation | Open: by design; notes and memories carry facts across orders |
| 6 | Mobs far below or above set off flees (XZ-only radius) | Fixed on dev: 16 blocks straight-line (F's call) |
| 7 | A chasing zombie hit Claude after one flee | Fixed on dev: quiet re-flee every 2 s within 6 blocks |
| 8 | Claude didn't know where the chest was | Fixed on dev: `points` lists nearby containers, ender chests, crafting tables and bed heads with block positions, beside F's map |
| 9 | No way to take or deposit items | Fixed on dev (F): `store` and `take` |
| 10 | Ender chest read as "tool not available" | Fixed on dev: `failed:ender_chest_personal` plus a prompt line |
| 11 | Claude repeated its own wrong "tool isn't available" | Fixed on dev: the prompt says the tool list is the truth |
| 12 | Token cap hit after about 32 calls (cache reads at full weight) | Fixed on dev: cache reads count a tenth |

Playtest 2 (23 Oct) asked for level gaze, crops on the map, farming, buckets, combat, vision and the text Brain panel; all are on dev now.

## Decisions
- Stated by F (locked): plan §2.
- D1 to D8: the skeleton follows every proposed default. That means the Messages API with a key; players never attacked and orders only from the allowlist; level 5 never swings; a 32-block work area; big-step planning; single-player first; mod id `claudeplayer`, package `com.example.claudeplayer`. F confirms or overrides each one here.
- Git (D7): https://github.com/F-F-Bukharin/Claude-In-Craft, branches `main` and `dev`, MIT license (`LICENSE` and `mod_license`). Commits carry a `Co-Authored-By` trailer until F decides otherwise.
- Made during the build, for F to review:
  - The body is a server-driven `ServerPlayer` (`AgentPlayer`), not Forge's `FakePlayer`, which doesn't tick, can't be hurt and never dies. It counts as a real player (tab list, sleep vote).
  - Pure logic lives under `core/`, and PurityTest enforces it.
  - `max_tokens` is 4096 instead of 1024, because Haiku 5.5 thinks by default and thinking counts against it. `effort` is low.
  - Pacing keeps the leftover credit. The planned formula gives 2.86 actions per second, not 3.
  - `tool_phase` (default 2) exposes tools as gates pass.
  - `idle_reactions` is on: a threat while no order runs starts one reaction call.
  - Memory, added at F's request: short-term notes (in RAM, fading) and long-term memories (`<world>/claudeplayer/memory.json`), plus the `inspect_container` tool.
  - Option B is built and is the default, at F's request: `brain = "claude_code"` runs F's `claude` on their subscription, as a warm stream-json session with `--tools ""`, `--setting-sources ""`, `--strict-mcp-config`, a replaced system prompt (file), and a `--json-schema` for `{say, calls}`. `--bare` is avoided because it ignores the subscription login. It runs in `%TEMP%/claudeplayer-claude-code`, so no CLAUDE.md is loaded. `brain = "api"` restores the Messages API.

## Claude Code facts (spikes from spec section 11, run 23 Oct with claude.exe 2.1.299, Haiku 5.5, effort low)
- S1, yes: in one `--input-format stream-json --output-format stream-json --verbose --json-schema` session, every turn's `result` carries `structured_output`. The cache works across turns: cache_read went 1615, then 1725, then 2033.
- S2, yes: a spare started on stream-json input sits idle with no output until its first line. Its first turn took 4.1 s, against 7.7 s for a cold start. Warm later turns took 2.3 to 4.3 s, depending mostly on output length.
- S5, partly: `rate_limit_event` came only on a session's first turn, not on every turn. It carries `utilization` and `resetsAt` per window, and `status` (`allowed_warning` past `surpassedThreshold` 0.75). The ledger gets one reading per session unless it asks again.
- S6, no: an idle `claude.exe` (no tools) starts no child processes, so `destroyForcibly` is enough and the tree kill is cheap insurance.
- With a system prompt that doesn't explain the JSON reply, Haiku tried a native tool call ("No such tool available: say") before answering. `ClaudeCodeBrain`'s HOW_TO_ACT section prevents that; keep it.
- Images: see glance, under "Next".

## Rules that bit this project (keep them)
- `core/` is pure Java (PurityTest).
- Game objects only on the server thread.
- `reflex` and `interrupt` are called only from `DamageWatcher.tick()`.
- Players are never attacked: `CautionPolicy.check` runs before every swing, including inside `fight`.
- Policy lives in code; a denial goes back to the model with its reason.
- Transcripts are append-only.
- Never log prompts, keys or image data at INFO.
- Builders work in their own worktrees, never two on the same files at once. Merge into `dev` locally, build green, and push only on F's word.

## Risks to watch
- Token use during busy play (plan §3 and §9). Watch the count in `/cp status`.
- The body counts toward the sleep vote. In single-player, use `/gamerule playersSleepingPercentage 50` until that's handled.
- Prefetch counts starts, so a batch heavy on moves may plan its next step early. Tune `prefetch_at` if it does.
- The project lives in OneDrive, so `build/`, `.gradle/` and `run/` get synced. Consider excluding them, or moving the project out.

## How to pick up
- To add a tool: a record in `core/act/Action.java`, an entry in `core/brain/ToolSchemas.java` (with a phase), a branch in `body/ActionExecutor.java` (or a core runner like `MemoryActions` for pure tools), and a ToolParseTest case. The pinned tool lists in ToolParseTest and ApiBrainTest (28 tools at phase 2) move with it.
- Team (F, 24 Oct): Opus 5.5 plans, reviews, and builds the important fixes and new pieces; Haiku 5.5 builds the small, numerous fixes; Fable for a structure review. Builders run in parallel worktrees, commit on their branch, and never push. Briefs quote exact files.

## Gate log

| Gate | Status | Notes |
|---|---|---|
| G0 spikes | passed in game | Spike A: spawn, demo, movement, death and respawn worked. Spike B: keycheck worked; bad-key check open |
| G1 skeleton | passed in game | |
| G2 brain | mostly passed | orders, crafting and mining worked in playtest 2; P1 to P3 open |
| G3 movement | code in, `tool_phase` 2 | follow moved to phase 2 |
| G4 caution | code in, `tool_phase` 4 | truth-table tests pass; F's profile runs phase 4 |
| G5 soak | open | |

## Files
- `docs/claude-player-plan.md`: decisions, architecture, contracts, phases, execution, tests, risks. From the planning pass; the modmap records where the build differs.
- `docs/claude-player-modmap.md`: rules, layout, systems, keys, tools, facts, status.
- `docs/claude-player-handoff.md`: this file.
- `docs/superpowers/`: the Claude Code bridge spec and the piece 1 plan.
- `helper/`: the standalone Claude Code helper, its own build; `helper/dist/HOW-IT-WORKS.txt` (also in the zip) is its guide.
- `README.md`: setup and use. `CLAUDE.md`: rules for agents working in this repo.

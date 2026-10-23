# Next session: handoff (23 Oct 2026, account switch)

For the next Claude session, on any account. Read this first, then `CLAUDE.md`, then the modmap RULES. The living handoff (`claude-player-handoff.md`) has the in-game check lists. This file says where the work stands right now.

## Where things stand

- **GitHub:** `dev` and `main` are both at `1669171`.
- **Local `dev` is ahead of GitHub and not pushed.** Its tip is `47b343a` (plus docs), and it holds:
  - level gaze while walking, and crops and farmland on the map (`fix/gaze-and-crops`);
  - the Brain panel as a live text screen (`feat/claude-brain-text-gui`, built by its own session);
  - `till`, `plant`, `harvest` and `use_bucket` (`feat/farming-buckets`);
  - text vision, the `see` tool (`feat/text-vision`);
  - the combat routine, `fight` with auto-gear (`feat/combat-routine`).
  - `./gradlew build` is green with 253 tests.
  - Push only after F says so. F's flow: pull `dev`, push `dev`, fast-forward `main`.
- **Gradle needs Java 17:** `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"`. The PATH Java is a 32-bit Java 8 that breaks Gradle.
- **F's test profile:** `C:\Users\Huntress\curseforge\minecraft\Instances\Claude-In-Craft`.
  - Its jar is from `1669171`, not current `dev`.
  - Its config has `trigger = "C"` and `tool_phase = 4` (attacks on).
  - Install by copying `build/libs/claudeplayer-0.1.0.jar` into its `mods` folder, only while the game is closed (Windows locks the jar).
- **The game log** to read after F plays: `C:\Users\Huntress\curseforge\minecraft\Instances\Claude-In-Craft\logs\latest.log`. Look for `reflex` lines and `claude code reply ... tokens` lines.

## Work in flight when this was written

Builders were running in git worktrees under `.claude/worktrees/`. Each was told to commit on its branch and write a report. They may have stopped at a usage limit. Check each branch's `git log`, and read its report if the file exists. The reports are in `C:\Users\Huntress\AppData\Local\Temp\claude\C--Users-Huntress-OneDrive-Documents-Faculdade-Mods-Claude-In-Craft\26f10b8d-daec-48fa-b2ce-a6b4c1252d3c\scratchpad\`.

| Branch (base) | What | Report | State |
|---|---|---|---|
| `feat/combat-routine` (78c0e13) | `fight(entity_id)`, a routine like an iron golem: it gears up (best weapon, armor and shield, picked in core `Gear`), walks to the target and swings when the cooldown is ready, with no model call per swing. `CautionPolicy.check` runs before every swing, and a denial ends the fight, which is what limits the chase per level. Reflex FIGHT queues `Fight`. It also stops on lava, fire or a deadly drop. | (none; summary below) | **merged into local `dev` as 47b343a; build green, 253 tests** |
| `feat/glance-model-side` (78c0e13) | Glance part 1: `Action.Glance`, the `glance` tool, the core `VisionGate` (15 s cooldown, one per turn), a PNG payload on `ActionResult`, image blocks in `tool_result`, the image surviving an early report, ApiBrain logs without base64, and ClaudeCodeBrain's warm session sending real image blocks. The executor returns `failed:no_client` until part 2. | `glance-model-report.md` | running |
| `fix/chase-heard` (old base, before the merges) | Review fixes for the chase rule. F1 is committed (`93d292f`: only a mob the model heard about can chase). F2 (a NONE decision rechecks after 10 ticks) is half done in `chase-heard-wip-F2.patch` in the scratchpad. F3 (a test for `reflexQuiet`'s `checkProgress`) and F4 (the Guardrails javadoc, and `/cp status` saying "weighted tokens") are not started. | none | stopped; rebase onto `dev` first |

Combat follow-ups:
- The modmap still needs two lines:
  - SYSTEMS 9: "A FIGHT queues `fight` (the routine) at level 3."
  - TOOLS phase 4: "`attack_entity` (one swing) and `fight` (gear up, walk and swing until it ends), behind CautionPolicy; only flee, stop, attack_entity and fight may ask for prio 3."
- In-game checks: a zombie at each caution level 0 to 5 (0 chases up to 16, 2 holds within 6, 3 and 4 fight only adjacent with no way out, 5 never fights); gear swaps (a sword from the bag, a better chestplate, a shield into an empty off-hand); a player beside a zombie is never hit.
- Watch: at levels 3 and 4, a mob stepping back past 2 blocks ends the fight, and the chase rule may loop flee and fight. An elytra or carved pumpkin gets swapped for real armor.

Merge order: `feat/glance-model-side` into `dev`. Both add entries to `Action`, `ToolSchemas`, `ActionExecutor`, the prompt, the per-phase tool lists in `ToolParseTest` and the tool count in `ApiBrainTest`; resolve those by keeping both. Build green, then install.

Proven: Claude Code with the brain's lockdown flags takes a base64 PNG image block over stream-json mid-session, and Haiku 5.5 reads it. Image tokens are about w×h/750, so 320×180 is about 80 tokens.

## Next, in order

1. **Land what's in flight:** combat, glance part 1, the chase-heard remainder.
2. **Glance part 2, the renderer.**
   - A client-only class behind `FMLEnvironment.dist == Dist.CLIENT`.
   - The server thread asks the client thread (`Minecraft.getInstance().execute`) to render the level from the body's eyes (the client entity with the same network id) into an off-screen `TextureTarget` of `vision_width`×`vision_height`, with no HUD and no hand.
   - Read it back to a PNG, restore the camera entity, the main target and the viewport, and hand the bytes back with `server.tell`.
   - Also `/cp glance`, which saves `<game dir>/claudeplayer/glance-<time>.png` so F can check what Claude sees.
   - There are no decompiled sources in the Gradle cache; check signatures with `javap` on the mapped Forge jar (the path is in `glance-vision-report.md`).
   - If a clean off-screen render isn't possible in 1.20.1, say so; don't ship something half-working.
3. **Install and playtest:** the checks in `claude-player-handoff.md` "Still to do": playtest-1 checks R1 to R5 and P1 to P3, reflexes M1 to M5, Brain panel B1 to B4, plus farming, buckets, fight, `see` and `glance`.

## Ideas: what we could and should do

**F's top asks (23 Oct):**
- **Bigger vision range.** F: "24 blocks is miniscule, he didnt see a tree on top of a hill."
  - Raise the map and `look` range to about 64, sampled coarser with distance, for example 1 block per cell near and 4 per cell far.
  - Make it height-aware: mark the top block of each column, so trees and hills above Claude's feet level show up (today the map is a slice at foot level, crops excepted).
  - Raise `see`'s ray length from 32 to about 96. Rays are cheap, but keep the rule that rays stop at unloaded chunks.
  - Add a landmarks line to `see`/`look`: the highest point, the nearest trees, water, and structures, each with direction and distance.
- **A `roam` skill, next to exploring.** `roam(radius, minutes)`: wander around the start point, picking waypoints toward directions not seen yet. Note what it finds (trees, ores, water, villages, animals) as short-term notes with positions, then walk back to where it started and report once. It's a routine like gather and dig, at level 1, so danger scraps it.

**Should do:**
- **Usage warnings** (spec piece 4):
  - read `rate_limit_event` (`five_hour` and `seven_day` utilization) from the warm session's stream-json;
  - warn in chat at 80% and 90%;
  - from 80% on, put caveman (the installed `caveman/SKILL.md` text) in the prompt;
  - show it in `/cp status`.
  - This matters on a Pro plan: F hit 97% of the weekly limit while testing.
- **Think-harder:** an offer of `(y)/(n)` to switch to `claude_think_effort = max`, never while a hostile is within 16 blocks horizontally (spec section 8).
- **Blockman** (spec piece 2, partly done by F's short chat): the pinned ORDER/STEP/POS/THREATS/QUEUE block at the end of each turn, so Claude doesn't lose track of the order.
- **Small fixes:**
  - `store` and `take` on an ender chest should return `ender_chest_personal` (only `inspect` does);
  - `HungerWatcher` still uses `hostilesXZ`, so decide whether 16 blocks straight-line fits it too;
  - the lava refusal `lava_near_player` is `failed:`, while other policy refusals are `denied:`;
  - `use_bucket` can't choose lava while it also carries a water bucket, so add an optional `fluid` argument;
  - measure the snapshot with `map` plus `points` against the 2 KB target;
  - the modmap still has stale "Phase 3" and "Phase 6, stubs" lines;
  - piece 1's follow-ups are listed in "Still to do" item 8.
- **Cleanup:** remove the finished worktrees under `.claude/worktrees/` (`git worktree remove`), and add `.claude/` to `.gitignore`.

**Could do:**
- Sleep in a bed at night (the body counts toward the sleep vote).
- A `build` routine from a short block list.
- Fishing.
- Sorting chests.
- Trading with villagers.
- Taming.
- A `home` command so Claude goes back and stores loot.
- **Later:** the friends package (a CurseForge export, a setup `.bat` that installs Claude Code and signs in, and a START HERE file). Friends use their own Claude accounts.

## Rules that bit this project (keep them)

- `core/` is pure Java (PurityTest).
- Game objects only on the server thread.
- `reflex` and `interrupt` are called only from `DamageWatcher.tick()`.
- Players are never attacked: `CautionPolicy.check` runs before every swing, including inside `fight`.
- Policy lives in code; a denial goes back to the model with its reason.
- Transcripts are append-only.
- Never log prompts, keys or image data at INFO.
- Builders work in their own worktrees, never two on the same files at once. Merge into `dev` locally, build green, and push only on F's word.

# Branch handoff: `claude/gifted-newton-nrhrer` (23 Oct 2026, cloud session)

Start here to merge this branch into `dev`. Read it with `dev`'s own handoff, `docs/claude-player-next-session.md` (written by the session on F's machine), then `CLAUDE.md` and the modmap RULES.

## Two lines of work since `d5b0bde`

Both started from `d5b0bde` ("Gather: don't call the inventory full before the first dig") and have not met since.

- **`dev`** (F's machine, pushed at `ca91c94`; `main` at `1669171`):
  - playtest 1 fixes (positions, `points`, 16-block straight-line reflexes, quiet chase re-flee, ender chests, cache reads at a tenth);
  - the Brain panel as a text screen;
  - farming and buckets, `see`, the `fight` routine, and glance part 1.
  - Its next steps are glance part 2 (the renderer) and a playtest.
- **This branch** (cloud, pushed; 301 unit tests green), in order:
  1. **Handoff item 8, piece 1 follow-ups.** Abandon spares only `reflex-` entries. Interrupts during one call are appended. The model hears a failed reflex. `follow_player` checks hazards. Same-prio prompt line. A slab, stair or carpet is ground. No flee for a hit from beyond `hostile_range`.
  2. **Bridge bugs 1, 2, 4 and 5.** The stdin write timeout, a reset during take, kills off the server thread, and the claim against the sweep race. `fix/warm-sessions` (3328d70, on F's machine) is superseded.
  3. **Blockman.** The `<status>` block at the end of every turn.
  4. **Done replies.** A simple order costs one model call.
  5. **Piece 4.**
     - `claude_effort` (high);
     - the usage ledger, with warnings at 80% and 90% and the limit message;
     - caveman from the installed skill;
     - think-harder (offer, y/n, hostile gate, session restart at max).
  6. **Token cap.**
     - Cache reads count a tenth.
     - The cap is per rolling hour, and `/cp resetcap` clears it.
     - Autonomy waits out the cap.
     - Autocompact (`compact_at_tokens`, 16000).
  7. **Opus code review.** `/code-review` found 46 issues. 4 are fixed (fcacf71, 53836d3, 9e7de28); the rest are listed in `claude-player-handoff.md` item 13.

## What `dev`'s next-session list calls open that this branch already did

- "Usage warnings (spec piece 4)", "Think-harder" and "Blockman": done here.
- "Piece 1's follow-ups ... item 8": done here.
- The "Known bridge bugs, open" (1, 2, 4, 5): done here.
- "Next latency win: let a reply mark the order done": done here.
- "Token cap hit after about 32 calls": both sides fixed it (`dev` f0e9e0c, here 30204e6). This branch also has the per-hour cap, `/cp resetcap` and autocompact.

## Merging into `dev`: the trial merge

A trial merge of this branch into `origin/dev` (`ca91c94`), run in a throwaway worktree and aborted, conflicts in 12 files. How to resolve each:

| File | Resolve by |
|---|---|
| `core/brain/Brain.java` | Both added `Usage.weighted()`. Keep one (this branch rounds the tenth up). |
| `core/safety/Reflexes.java` | Keep `dev`'s `decide(level, s, lit, attacksEnabled)`: FIGHT runs the fight routine at any range, so this branch's `reach` argument goes. Add this branch's `inFleeRange` as a fifth argument. It turns FLEE and FLEE_AND_CALL_FOR_HELP into NONE when false, and makes the no-attacks fallback NONE instead of FLEE. |
| `event/DamageWatcher.java` | Keep `dev`'s three-field `Pending` and its straight-line scan. Compute `inFleeRange` with `SnapshotBuilder.hostiles(p, hostileRange)` (`dev`'s straight-line scan, which flee also uses), not `hostilesXZ`. |
| `test/.../ReflexesTest.java` (6 hunks) | Keep `dev`'s tests, and add this branch's two flee-range tests without the `reach` argument. |
| `core/brain/ClaudeCodeBrain.java` | `dev` renamed `renderTranscript` to `renderContent` (image blocks). Keep that name with this branch's `write(..., timeout)` and `refill(spareCommand)`. |
| `core/brain/AgentLoop.java` (3 hunks) | (1) `dev`'s `reflexEntries(actions)` plus this branch's `thinkHarder = false`. (2) `dev`'s `content.addAll(lateImages)` plus this branch's status block and compact branch. Autocompact turns tool results into text, so images in them are dropped; leave `lateImages` out of a compact turn, or re-attach it after the text. (3) Keep both resets (`lateImages`, `lastPrompt`). |
| `core/Settings.java`, `Config.java` | Keep both sets of keys: `dev`'s vision keys and this branch's effort, think-harder, usage, caveman and compact keys. |
| `resources/claudeplayer/system_prompt.md` | Both edited the prio line. Merge both sentences (this branch's same-prio rule, and `dev`'s change). |
| `test/.../AgentLoopTest.java`, both docs | Keep both sides. |

After the merge, before calling it green:
- **Chase re-flee:** `dev`'s `reflexQuiet(...)` should also end think-harder, as `reflex` and `interrupt` do now.
- **Tool counts:** check `ApiBrainTest`'s count against the merged tool list.
- **Glance:** check that the image-in-tool-result path still works across an early report and a compaction.
- **Reflex radius:** decide whether `HungerWatcher` and the think-harder gate (which use `hostilesXZ`) move to the straight-line radius.
- **Gate:** run `./gradlew build`. F's rule: merge into `dev` locally, build green, push only on F's word.

## Open work on this branch, in priority order

The full list, with each planned fix, is in `claude-player-handoff.md` item 13. The top of it:

1. **Think-harder runtime.** A bare `C y` or `C n` with no open offer becomes an order. The gate isn't re-checked on yes. A held order survives death, despawn and quickstart. The fix was written and then reverted unapplied when the session stopped: `ThinkHarder.isAnswer()` and `offeredTo()`, a `threatNear()` helper, and dropping the held order in `onBodyDied` and quickstart.
2. **Autocompact.**
   - The caveman note is lost on compaction.
   - Summary codes freeze at `queued`; track them per call id, with the call's inputs, instead of parsing notes.
   - A reaction's compact turn loses its event text, and `<recent_chat>` is dropped.
   - `<earlier>` and `<results>` are not in the prompt's data-tag list.
   - The minimum and hysteresis for `compact_at_tokens` need raising.
   - `ledger.startOrder()` fires on the compacted transcript.
3. **Usage ledger.**
   - A 5-hour rejection outlives its window.
   - Thresholds re-arm on any later `resetsAt`.
   - Stale windows show in `/cp status`, and reset times are UTC with no marker.
   - The file is written under the lock.
   - Warnings are lost when no allowlisted player is online.
   - `usage_warn_at` rejects whole numbers.
4. **Nav.** An open trapdoor, ladder or chain over a shaft counts as ground.
5. **Then:**
   - the caveman search depth and symlinks, and paths in INFO logs;
   - the clean-ups (double command build, the spare killed and relaunched, triple hostile scan, API-brain loading, duplicated atomic write and sha256, dead `rateLimitEvent`, `cancelIf` duplication);
   - a `/simplify` pass;
   - the modmap's stale token-cap lines.

## In-game checks this branch adds

Run these in `claude-player-handoff.md` on this branch:
- **Item 8:** follow along lava, a slab bridge at low HP, and a far ghast hit.
- **Item 11:** "Latency win", where `C come here` makes one `claude code reply` line.
- **Item 12:** `/cp status` usage lines, think-harder with and without a zombie, and caveman at `caveman_at = 0`.
- **Item 13:** a long order compacts (`compacted order transcript` in the log), and `/cp resetcap`.
- **Unconfirmed:** the real `rate_limit_event` nesting and what the CLI sends at 100%. Check `config/claudeplayer_usage.json` after a few orders.

## Conventions this session followed

- **Commit identity:** commits use F's noreply identity, `F. F. Bukharin <262671839+F-F-Bukharin@users.noreply.github.com>`.
- **No trailers:** commits carry no attribution trailer. The cloud stop hook asks to re-author commits as Claude; F's rule is to ignore it.
- **One commit per verified unit**, test first, with the full gate before each commit.
- **Cloud environment:** Gradle needs `maven.minecraftforge.net`, so the environment's network access is Full. Auto mode blocks history rewrites, so use merges and cherry-picks.

# Claude Player

A Forge mod for Minecraft 1.20.1 that adds a player named Claude. Allowlisted players give orders in chat (`claude: come here`). The mod sends the order and a snapshot of the world to Claude through the Messages API. Claude answers with tool calls, which run at 3 actions per second, and reports back in chat. Its caution level (0 to 5) decides when it may fight, and the code enforces that, not the prompt.

Status: skeleton, rules and brain are built and unit-tested. Nothing has run in game yet; see `docs/claude-player-handoff.md`.

## Setup
1. JDK 17 or newer on the PATH. Gradle downloads the Java 17 toolchain and Minecraft itself.
2. `./gradlew build` builds the jar (`build/libs/`) and runs the tests.
3. `./gradlew runClient` starts a dev client. Open a single-player world with cheats on, then type `/cp quickstart`.
4. API key: set `ANTHROPIC_API_KEY`, or put the key on the first line of `config/claudeplayer.key` (`run/config/` in dev). Never commit it; `.gitignore` covers `*.key`.

One command for all of it: `./gradlew runClient -Pquickstart="Claude Test"` opens the saved world named "Claude Test" and runs `/cp quickstart` once you're in. Create that world once first. With `-Pquickstart` and no name, it runs in whichever world you open.

## In game
| Command | Who | What |
|---|---|---|
| `/cp help` (or just `/cp`) | anyone | Instructions; click a line to put that command in chat |
| `/cp quickstart` | ops, allowlisted | Allow yourself, bring Claude to you, run the demo, report whether a key is set |
| `/cp allow <name>`, `/cp deny <name>` | ops | Edit the allowlist |
| `/cp spawn` | ops, allowlisted | Spawn Claude here; this becomes home |
| `/cp despawn`, `/cp respawn` | ops, allowlisted | Remove Claude, or bring it back at home after a death |
| `/cp stop` | ops, allowlisted | Kill switch: cancel everything, block model calls until the next order |
| `/cp status`, `/cp memory` | ops, allowlisted | Level, queue, calls, tokens; what Claude remembers |
| `/cp keycheck` | ops, allowlisted | Free API call that checks the key and model |
| `/cp level <0-5>` | ops | Set the caution level |

Chat, from allowlisted players: `claude: <order>`, `claude: stop`, `claude: respawn`, `claude: level <0-5>`, and `claude: demo` (walks a small square with no API call).

Caution levels: 0 guns blazing, 1 bloodthirsty, 2 ready and willing, 3 reasonable (default), 4 cornered animal, 5 innocent. Players are never attacked at any level.

## Memory
- Short-term: exact, recent observations such as "chest at 10, 64, 5: 15 oak_log, 17 cobblestone, 64 torch". They stay in RAM and fade after 10 minutes. `inspect_container` records them automatically, and Claude can add its own with `note`.
- Long-term: durable facts Claude chooses to keep with `remember`, such as "chest@10,64,5: building materials". They're saved per world in `<world>/claudeplayer/memory.json`, which you can read and edit.
- Both appear in every order's first turn, marked as data. `forget` deletes a subject from both.

## Config
`config/claudeplayer-common.toml`, created on first launch. Every key is documented in the file and in the modmap's KEYS section. The ones you're most likely to change are `orders_allowlist`, `model`, `session_token_cap`, `tool_phase` and `default_level`. To change Claude's instructions, copy `src/main/resources/claudeplayer/system_prompt.md` to `config/claudeplayer_system_prompt.md` and edit it.

## Layout
- `core/`: pure Java, unit-tested without Minecraft. Actions and the paced queue (`act`), the API client and agent loop (`brain`), chat intake (`chat`), memory (`memory`), and the caution policy, guardrails and text sanitizer (`safety`).
- `body/`: the player entity, its lifecycle and saved data, the world snapshot, and action execution.
- `nav/`: path following and the escape check. `event/`: Forge events and threat interrupts. `command/`: `/cp`.
- `docs/`: the plan, the modmap (the canonical map for contributors) and the handoff.

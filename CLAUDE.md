# Claude Player (Claudecraft)

Forge 1.20.1 mod: a player named Claude that takes chat orders from allowlisted players and acts through tools, driven by the Claude Messages API.

Read `docs/claude-player-modmap.md` (RULES first) before changing anything; `docs/claude-player-handoff.md` has the state and next steps.

## Commands
- Build and test (the gate): `./gradlew build`. Tests only: `./gradlew test`. Run the game: `./gradlew runClient`.
- Gradle runs on any JDK 17+; the Java 17 toolchain is provisioned automatically.

## Rules that bite
- `core/` is pure Java: no `net.minecraft`, `net.minecraftforge` or `com.mojang` imports. PurityTest fails the build otherwise. Game code lives outside `core/`.
- Game objects only on the server thread. HTTP only on the brain thread; results come back through `AgentRuntime.onMainThread` (`server.tell`, never `execute`).
- Policy lives in code, not the prompt: CautionPolicy and Guardrails decide; a denial goes back to the model with its reason.
- World text (names, signs, chat, memory) passes through `WorldText` before reaching a prompt.
- Never log the API key. INFO logs carry ids, stop reasons, tokens and results only.
- Transcripts are append-only per order (thinking blocks and prompt caching depend on it).
- Claude gets no tool that changes its caution level, the allowlist, the config, or runs commands.

## Adding a tool
Record in `core/act/Action.java` → schema and parser in `core/brain/ToolSchemas.java` (with a phase) → execution in `body/ActionExecutor.java` (or a core runner such as `core/memory/MemoryActions.java`) → a case in `ToolParseTest`.

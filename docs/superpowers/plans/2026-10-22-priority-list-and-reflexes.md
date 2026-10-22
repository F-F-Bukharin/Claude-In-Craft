# Priority List and Reflexes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every queued action gets a priority from 1 to 3, a level 3 preempts (level 1 is scrapped, level 2 is paused and resumes), and code reflexes flee or fight by caution level before the model hears about a threat.

**Architecture:** `ActionQueue` keeps one FIFO per level and preempts when a level-3 entry arrives. `ToolSchemas` gives every tool an optional `prio` and adds `flee`. A pure `Reflexes` class decides the move and spots hazards. `AgentLoop` gains `reflex(reason, actions)`, which enqueues level-3 actions and reports what was paused or scrapped. The game side (DamageWatcher, MoveHandle, EscapePlanner, ActionExecutor) feeds it. This works with today's API brain.

**Tech Stack:** Java 17, Forge 1.20.1 (47.4.28, official mappings), JUnit 5, Gson.

**Spec:** `docs/superpowers/specs/2026-10-22-claude-code-bridge-design.md`, sections 2, 6 and 10 (piece 1 of section 13).

**Branch:** `feat/priority-reflexes` off `main`. One commit per task.

## Global Constraints

- `core/` stays pure Java: no `net.minecraft`, `net.minecraftforge` or `com.mojang` imports. PurityTest fails the build otherwise.
- Game objects only on the server thread.
- Policy lives in code. A denial goes back to the model with its reason.
- World text (names, signs, chat) passes through `WorldText` before it reaches a prompt.
- Adding a tool: a record in `core/act/Action.java`, then a schema and parser in `core/brain/ToolSchemas.java` (with a phase), then execution in `body/ActionExecutor.java`, then a `ToolParseTest` case.
- Levels are exactly 1, 2, 3; the default is 1. Level 3 is accepted only on `flee`, `stop` and `attack_entity`. Any other tool asked at 3 runs at 2.
- A level-3 arrival scraps every level-1 entry (running or waiting), pauses a running level-2 entry (it goes back to the head of level 2), and never interrupts a running level 3.
- A level-3 entry starts without waiting for pacing credit. The 3-per-second pace applies to everything after it.
- The reflex radius is `hostile_range` (16), measured on XZ with no vertical cap.
- A creeper with a lit fuse means FLEE at every caution level.
- Players are never attacked, by the model or by a reflex.
- New config key `reflexes`, default `true`.

## Spec amendments

The plan differs from spec section 6 in five places. Task 5 writes them into the spec.

1. **Hazard.** The move is refused: the body halts and the move fails with `failed:hazard_lava`, `hazard_fire` or `hazard_drop`. There is no separate level-3 `stop` entry, because a stop would also cancel paused level-2 work. Each later move runs the same check.
2. **`prio_lowered`.** It goes out as a note in the next turn, not in the result code, so result codes stay one code each.
3. **FIGHT reflex.**
   - A single swing, and only when the threat is within attack reach (3 blocks). Farther away, Claude is told and plans the approach.
   - FIGHT while attacks are off (`tool_phase` < 4) becomes FLEE.
   - Players and non-hostile mobs never trigger a reflex.
4. **Safety tools** are `flee`, `stop` and `attack_entity`. No eat tool exists yet.
5. **Reflexes still run after `claude: stop`.** They protect the body and its items. No model call follows while the kill switch is on.

## Review Focus

1. A player or a neutral mob (wolf, golem, bee) hits Claude: no reflex attack, ever. Pinned by `playersAndNonHostilesGetNoReflex` (Task 3).
2. Two creepers light within the 5-second interrupt gap: both trigger a flee. Pinned by `litFusesAreNotHeldBackByTheGap` (Task 3).
3. A reflex lands while a model call is out: the reply's plan is voided, and the note names the reflex. Pinned by `aReflexDuringACallVoidsTheRepliesPlan` (Task 4).
4. After `claude: stop`, a creeper approaches: the body still flees, and no model call goes out. Pinned by `aReflexAfterStopStillRunsButCallsNoModel` (Task 4).
5. A second danger while already fleeing, after Claude queued new level-1 work: the new work is scrapped and the running flee is untouched. Pinned by `aSecondLevelThreeScrapsNewRoutineWorkButNotTheRunningOne` (Task 1).

---

### Task 1: Priority levels in ActionQueue

**Files:**
- Modify: `src/main/java/com/example/claudeplayer/core/act/ActionResult.java`
- Modify: `src/main/java/com/example/claudeplayer/core/act/ActionQueue.java`
- Test: `src/test/java/com/example/claudeplayer/core/act/ActionQueueTest.java`

**Interfaces:**
- Produces:
  - `ActionResult.Status.SCRAPPED`, `ActionResult.scrapped()`, code `"scrapped"`, `isError()` false.
  - `ActionQueue.Entry(String callId, String tool, Action action, int prio)`. The compact constructor throws `IllegalArgumentException` unless `1 <= prio <= 3`. The 3-argument constructor `Entry(callId, tool, action)` means prio 1.
  - `ActionQueue.Preemption(List<Entry> scrapped, List<Entry> paused)`, with `static final Preemption NONE` and `boolean isEmpty()`. `paused` holds only the running level-2 entry that was interrupted.
  - `Preemption enqueue(List<Entry> entries)`. It preempts first, then adds the new entries.
  - `void cancelBelow(int prio)`: cancels the running entry and every waiting entry whose prio is below `prio`; each reports `cancelled`.
  - `cancelAll()` and `Stop` keep their meaning: everything goes. Waiting entries report highest level first, FIFO within a level.

- [ ] **Step 1: Write the failing tests** in `ActionQueueTest`, using the existing runner (MoveTo never finishes; its cancel adds `"move cancelled"`):

```java
private static final Action MOVE = new Action.MoveTo(0, 64, 0);
private static final Action SAY = new Action.Say("hi");

private static ActionQueue.Entry e(String id, String tool, Action action, int prio) {
    return new ActionQueue.Entry(id, tool, action, prio);
}

private static List<String> ids(List<ActionQueue.Entry> entries) {
    return entries.stream().map(ActionQueue.Entry::callId).toList();
}

@Test
void higherLevelsStartFirst() {
    ActionQueue q = queue(3.0);
    q.enqueue(List.of(e("a", "say", SAY, 1), e("b", "say", SAY, 2), e("c", "say", SAY, 1)));
    run(q, 40);
    assertEquals(List.of("b=ok", "a=ok", "c=ok"), results);
}

@Test
void levelThreeScrapsLevelOneAndPausesLevelTwo() {
    ActionQueue q = queue(3.0);
    q.enqueue(List.of(e("m", "move_to", MOVE, 2), e("s0", "say", SAY, 1), e("s1", "say", SAY, 1), e("w", "say", SAY, 2)));
    run(q, 5); // m is running
    ActionQueue.Preemption pre = q.enqueue(List.of(e("f", "say", SAY, 3)));
    assertEquals(List.of("move cancelled", "s0=scrapped", "s1=scrapped"), results);
    assertEquals(List.of("s0", "s1"), ids(pre.scrapped()));
    assertEquals(List.of("m"), ids(pre.paused()));
    run(q, 1);
    assertEquals("f=ok", results.get(results.size() - 1), "level 3 starts in the next tick");
    run(q, 40);
    assertEquals("move_to", q.runningTool(), "m restarted after f");
    assertEquals(1, q.waitingCount(), "w waits behind m");
}

@Test
void aRunningLevelOneIsScrapped() {
    ActionQueue q = queue(3.0);
    q.enqueue(List.of(e("m", "move_to", MOVE, 1)));
    run(q, 2);
    q.enqueue(List.of(e("f", "say", SAY, 3)));
    assertEquals(List.of("move cancelled", "m=scrapped"), results);
}

@Test
void levelThreeSkipsThePaceAndThePaceResumesAfterIt() {
    ActionQueue q = queue(3.0);
    q.enqueue(says(3));
    run(q, 1); // s0 used the banked start; s1 and s2 get scrapped below
    q.enqueue(List.of(e("f", "say", SAY, 3), e("x", "say", SAY, 2)));
    run(q, 1);
    assertEquals(tick, (int) startTicks.get(1), "f started at once, without credit");
    run(q, 20);
    assertTrue(startTicks.get(2) - startTicks.get(1) >= 6, "x waited a full interval: " + startTicks);
}

@Test
void levelThreeWaitsBehindARunningLevelThree() {
    ActionQueue q = queue(3.0);
    q.enqueue(List.of(e("m", "move_to", MOVE, 3)));
    run(q, 2);
    assertTrue(q.enqueue(List.of(e("f", "say", SAY, 3))).isEmpty());
    assertEquals(List.of(), results);
    assertEquals(1, q.waitingCount());
}

@Test
void aSecondLevelThreeScrapsNewRoutineWorkButNotTheRunningOne() {
    ActionQueue q = queue(3.0);
    q.enqueue(List.of(e("f1", "move_to", MOVE, 3)));
    run(q, 2);
    q.enqueue(List.of(e("h", "say", SAY, 1)));
    q.enqueue(List.of(e("f2", "say", SAY, 3)));
    assertEquals(List.of("h=scrapped"), results);
    assertEquals("move_to", q.runningTool());
}

@Test
void cancelBelowKeepsLevelThree() {
    ActionQueue q = queue(3.0);
    q.enqueue(List.of(e("m", "move_to", MOVE, 3)));
    run(q, 2);
    q.enqueue(List.of(e("s", "say", SAY, 1), e("t", "say", SAY, 2)));
    q.cancelBelow(3);
    assertEquals(List.of("t=cancelled", "s=cancelled"), results);
    assertEquals("move_to", q.runningTool());
}

@Test
void priorityMustBeOneToThree() {
    assertThrows(IllegalArgumentException.class, () -> e("x", "say", SAY, 0));
    assertThrows(IllegalArgumentException.class, () -> e("x", "say", SAY, 4));
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests "com.example.claudeplayer.core.act.ActionQueueTest"`
Expected: compilation fails (`cannot find symbol` for the 4-argument `Entry`, `Preemption`, `cancelBelow`).

- [ ] **Step 3: Implement**

Add `SCRAPPED` and `scrapped()` to `ActionResult`, and update its javadoc line and the `code()` switch.

In `ActionQueue`, replace `waiting` with one `ArrayDeque<Entry>` per level (index `prio - 1`). `waitingCount()`, `idle()` and `cancelWaiting()` cover all three, highest level first.

`tick()` starts the highest waiting entry when nothing runs and either `credit >= UNIT` or the entry is level 3. Each start sets `credit = Math.max(0, credit - UNIT)`.

The preempt runs inside `enqueue` when any new entry is level 3. The order of listener calls matters, so this is the body:

```java
private Preemption preempt() {
    List<Entry> scrapped = new ArrayList<>();
    List<Entry> paused = new ArrayList<>();
    if (running != null && running.prio() < 3) {
        Entry r = running;
        cancelHandle();
        running = null;
        handle = null;
        if (r.prio() == 2) {
            level(2).addFirst(r);   // restarts from scratch once level 3 is done
            paused.add(r);
        } else {
            scrapped.add(r);
            listener.onFinished(r, ActionResult.scrapped());
        }
    }
    List<Entry> routine = new ArrayList<>(level(1));
    level(1).clear();
    for (Entry e : routine) {
        scrapped.add(e);
        listener.onFinished(e, ActionResult.scrapped());
    }
    return new Preemption(scrapped, paused);
}
```

Update the class javadoc with one line on the levels.

- [ ] **Step 4: Run the queue tests**

Run: `./gradlew test --tests "com.example.claudeplayer.core.act.ActionQueueTest"`
Expected: PASS, including the existing pacing, timeout, cancelAll and stop tests, which still use the 3-argument `Entry`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/claudeplayer/core/act/ActionResult.java src/main/java/com/example/claudeplayer/core/act/ActionQueue.java src/test/java/com/example/claudeplayer/core/act/ActionQueueTest.java
git commit -m "Queue: three priority levels; level 3 preempts, scraps level 1 and pauses level 2"
```

---

### Task 2: `prio` on every tool, and the `flee` tool

**Files:**
- Modify: `src/main/java/com/example/claudeplayer/core/act/Action.java` (add `record Flee() implements Action {}`, with a one-line comment like its neighbours)
- Modify: `src/main/java/com/example/claudeplayer/core/brain/ToolSchemas.java`
- Modify: `src/main/resources/claudeplayer/system_prompt.md`
- Test: `src/test/java/com/example/claudeplayer/core/brain/ToolParseTest.java`

**Interfaces:**
- Produces:
  - `ToolSchemas.Parsed(Action action, ActionResult error, int prio, boolean prioLowered)`. An error result carries prio 1, lowered false.
  - `public static final Set<String> SAFETY_TOOLS = Set.of("flee", "stop", "attack_entity")`.
  - Tool `flee`, phase 1, no required properties, parsing to `new Action.Flee()`.

- [ ] **Step 1: Write the failing tests** in `ToolParseTest`:

```java
@Test
void prioDefaultsToOneAndTwoIsKept() {
    ToolSchemas.Parsed p = v1.parse("move_to", json("{\"x\":1,\"y\":64,\"z\":1}"));
    assertEquals(1, p.prio());
    assertFalse(p.prioLowered());
    assertEquals(2, v1.parse("move_to", json("{\"x\":1,\"y\":64,\"z\":1,\"prio\":2}")).prio());
}

@Test
void prioThreeIsOnlyForSafetyTools() {
    assertEquals(3, v1.parse("flee", json("{\"prio\":3}")).prio());
    assertEquals(3, v1.parse("stop", json("{\"prio\":3}")).prio());
    assertEquals(3, new ToolSchemas(4).parse("attack_entity", json("{\"entity_id\":7,\"prio\":3}")).prio());
    ToolSchemas.Parsed lowered = v1.parse("say", json("{\"text\":\"hi\",\"prio\":3}"));
    assertEquals(2, lowered.prio());
    assertTrue(lowered.prioLowered());
}

@Test
void badPrioFailsWithTheField() {
    for (String bad : List.of("0", "4", "1.5", "\"high\"")) {
        assertEquals("failed:bad_input:prio", error(v1, "stop", "{\"prio\":" + bad + "}"), bad);
    }
}

@Test
void fleeIsAPhaseOneTool() {
    assertEquals(new Action.Flee(), new ToolSchemas(1).parse("flee", json("{}")).action());
}

@Test
void everyToolOffersAnOptionalPrio() {
    for (JsonElement d : new ToolSchemas(6).definitions()) {
        JsonObject schema = d.getAsJsonObject().getAsJsonObject("input_schema");
        assertTrue(schema.getAsJsonObject("properties").has("prio"), d.toString());
        assertFalse(schema.getAsJsonArray("required").contains(new JsonPrimitive("prio")), d.toString());
    }
}
```

The existing test that pins the phase-2 tool list (`ToolParseTest.java:68`) gains `flee` right after `stop`. This is a spec-driven change: `List.of("get_state", "move_to", "say", "wait", "stop", "flee", "note", "remember", "forget", "inspect_container")`.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests "com.example.claudeplayer.core.brain.ToolParseTest"`
Expected: compilation fails (`prio()`, `Action.Flee`).

- [ ] **Step 3: Implement**
  - `add()` puts a `prio` property, `{"type":"integer","description":PRIO}`, in every schema and leaves it out of `required`. Its description is this exact copy: `Priority 1 to 3, default 1. 1: routine, dropped if danger interrupts. 2: important, paused and resumed after danger. 3: only flee, stop and attack_entity; runs before everything else.`
  - `parse()` runs the tool's parser, then reads `prio` with the existing `Input.integer("prio", 1, 3)` when the key is present (add `Input.has(String)`). A level 3 outside `SAFETY_TOOLS` becomes 2 with `prioLowered` true.
  - Register `flee` in the v1 block, after `stop`, with this exact description: `Run from the hostiles around you to a spot well away from all of them. Holds the queue until you get there; failed:no_escape if there is no way out, ok with no_threats if nothing is near.`
  - In `system_prompt.md`, under "How acting works":
    - Replace "They run in order at {{actions_per_second}} actions per second" with "They run at {{actions_per_second}} actions per second, highest prio first and in order within a prio".
    - Add `scrapped` to the codes line, with "scrapped means danger interrupted it; redo it if it still matters."
    - Add the bullet: "- Each call takes an optional prio. 1 (default) is routine: when danger interrupts, it is scrapped. 2 is important: it is paused and resumes after the danger. 3 is only for flee, stop and attack_entity, and runs before everything else."
  - Under "Rules", add: "- When danger comes close, your body reacts by your caution level before you hear about it. The INTERRUPT note says what it did and what was paused or scrapped."

- [ ] **Step 4: Run the parse and loop tests**

Run: `./gradlew test --tests "com.example.claudeplayer.core.brain.*"`
Expected: PASS. `theBundledPromptFillsEveryPlaceholder` stays green, because the new text uses no new placeholders.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/claudeplayer/core/act/Action.java src/main/java/com/example/claudeplayer/core/brain/ToolSchemas.java src/main/resources/claudeplayer/system_prompt.md src/test/java/com/example/claudeplayer/core/brain/ToolParseTest.java
git commit -m "Tools: an optional prio on every tool (3 only for safety tools) and a flee tool"
```

---

### Task 3: Reflex decisions and hazards (pure core)

**Files:**
- Create: `src/main/java/com/example/claudeplayer/core/safety/Reflexes.java`
- Test: `src/test/java/com/example/claudeplayer/core/safety/ReflexesTest.java`

**Interfaces:**
- Consumes: `CautionLevel`, `CautionPolicy.Situation`, `CautionPolicy.advise`.
- Produces:
  - `public enum Reflexes.Move { FLEE, FIGHT, NONE }`
  - `public static double horizontal(double dx, double dz)`
  - `public static Move decide(CautionLevel level, CautionPolicy.Situation s, boolean litCreeper, boolean attacksEnabled, double reach)`
  - `public static String hazard(String feetBlock, String groundBlock, int dropDepth, boolean landsInWater, double hp)`: returns `"lava"`, `"fire"`, `"drop"`, or `null` when safe.

- [ ] **Step 1: Write the failing tests** in the new `ReflexesTest`:

```java
private static CautionPolicy.Situation s(double distance, double hp, boolean escape) {
    return new CautionPolicy.Situation(true, false, distance, distance <= 2.0, hp, escape, false);
}

@Test
void horizontalIgnoresHeight() {
    assertEquals(5.0, Reflexes.horizontal(3, 4), 1e-9);
}

@Test
void aLitCreeperMeansFleeAtEveryLevel() {
    for (CautionLevel level : CautionLevel.values()) {
        assertEquals(Reflexes.Move.FLEE, Reflexes.decide(level, s(2, 1.0, true), true, true, 3.0), level.name());
    }
}

@Test
void fleeStancesFlee() {
    assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.INNOCENT, s(10, 1.0, true), false, true, 3.0));
    assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.INNOCENT, s(10, 0.2, true), false, true, 3.0), "flee and call for help");
    assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.REASONABLE, s(8, 1.0, true), false, true, 3.0));
}

@Test
void fightOnlyWithinReach() {
    assertEquals(Reflexes.Move.FIGHT, Reflexes.decide(CautionLevel.READY, s(2.5, 1.0, true), false, true, 3.0));
    assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.READY, s(5, 1.0, true), false, true, 3.0), "Claude plans the approach");
}

@Test
void fightWithoutAttacksBecomesFlee() {
    assertEquals(Reflexes.Move.FLEE, Reflexes.decide(CautionLevel.READY, s(2.5, 1.0, true), false, false, 3.0));
}

@Test
void holdDoesNothing() {
    assertEquals(Reflexes.Move.NONE, Reflexes.decide(CautionLevel.READY, s(10, 1.0, true), false, true, 3.0));
}

@Test
void playersAndNonHostilesGetNoReflex() {
    CautionPolicy.Situation player = new CautionPolicy.Situation(false, true, 1.5, true, 1.0, true, false);
    CautionPolicy.Situation wolf = new CautionPolicy.Situation(false, false, 1.5, true, 1.0, true, false);
    for (CautionLevel level : CautionLevel.values()) {
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, player, false, true, 3.0), level.name());
        assertEquals(Reflexes.Move.NONE, Reflexes.decide(level, wolf, false, true, 3.0), level.name());
    }
}

@Test
void hazards() {
    assertEquals("lava", Reflexes.hazard("minecraft:lava", "minecraft:stone", 0, false, 20));
    assertEquals("lava", Reflexes.hazard("minecraft:air", "minecraft:lava", 0, false, 20));
    assertEquals("fire", Reflexes.hazard("minecraft:fire", "minecraft:stone", 0, false, 20));
    assertEquals("fire", Reflexes.hazard("minecraft:soul_fire", "minecraft:stone", 0, false, 20));
    assertEquals("drop", Reflexes.hazard("minecraft:air", "minecraft:air", 23, false, 20), "20 damage at 20 HP");
    assertNull(Reflexes.hazard("minecraft:air", "minecraft:air", 22, false, 20));
    assertNull(Reflexes.hazard("minecraft:air", "minecraft:air", 23, true, 20), "water breaks the fall");
    assertEquals("drop", Reflexes.hazard("minecraft:air", "minecraft:air", 6, false, 3));
    assertNull(Reflexes.hazard("minecraft:air", "minecraft:air", 3, false, 3), "3 blocks never hurt");
}

@Test
void litFusesAreNotHeldBackByTheGap() {
    ThreatTracker fuses = new ThreatTracker(0, 60);
    assertTrue(fuses.report("lit:1", 100));
    assertTrue(fuses.report("lit:2", 101), "a second creeper a tick later still counts");
    assertFalse(fuses.report("lit:1", 102), "one report per fuse");
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests "com.example.claudeplayer.core.safety.ReflexesTest"`
Expected: compilation fails (`Reflexes` doesn't exist).

- [ ] **Step 3: Implement `Reflexes`** (a final class with a private constructor, like `CautionPolicy`)

`decide` checks in this order:
1. `litCreeper` → FLEE.
2. `s.targetPlayer() || !s.targetHostile()` → NONE.
3. Otherwise map `CautionPolicy.advise`:
   - FLEE and FLEE_AND_CALL_FOR_HELP → FLEE.
   - HOLD → NONE.
   - FIGHT → FLEE when `!attacksEnabled`; FIGHT when `s.distance() <= reach`; NONE otherwise.

`hazard`:
- `"lava"` if either block is `minecraft:lava`.
- `"fire"` if the feet block is `minecraft:fire` or `minecraft:soul_fire`.
- `"drop"` if `!landsInWater && dropDepth - 3 >= hp`.

Javadoc names the units: hp in half-hearts as `LivingEntity#getHealth` reports it, and dropDepth in empty blocks below the probed feet block.

- [ ] **Step 4: Run the safety tests**

Run: `./gradlew test --tests "com.example.claudeplayer.core.safety.*"`
Expected: PASS (CautionPolicyTest and SafetyTest unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/claudeplayer/core/safety/Reflexes.java src/test/java/com/example/claudeplayer/core/safety/ReflexesTest.java
git commit -m "Reflexes: fight or flee by caution level, a lit creeper always flees, lava, fire and deadly drops"
```

---

### Task 4: The loop reacts with priorities

**Files:**
- Modify: `src/main/java/com/example/claudeplayer/core/Settings.java` (add `boolean reflexes` after `idleReactions` in the record, `public boolean reflexes = true;` in the Builder, and pass it in `build()`)
- Modify: `src/main/java/com/example/claudeplayer/Config.java` (a `reflexes` BooleanValue, defined and read like `IDLE_REACTIONS` at :74-76 and :116; comment: "Code reflexes: flee or fight by caution level, and refuse moves into lava, fire or deadly drops, before the model hears about it.")
- Modify: `src/main/java/com/example/claudeplayer/core/brain/AgentLoop.java`
- Test: `src/test/java/com/example/claudeplayer/core/brain/AgentLoopTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `Entry(.., int prio)`, `Preemption enqueue(..)`, `cancelBelow(int)`, `cancelAll()`.
  - Task 2: `Parsed.prio()`, `Parsed.prioLowered()`.
- Produces:
  - `public void reflex(String reason, List<Action> actions)`. Server thread only. Enqueues each action at level 3, with call ids `reflex-<n>`.
  - `public void interrupt(String reason)`. With reflexes on it reports at once and leaves the queue alone. With them off it cancels below level 3, as before.
  - The note format, exactly:
    - `INTERRUPT: <reason> Reflex: <tool>[, <tool>].`
    - then ` Paused: <tool> <id>[, ...].` when something was paused,
    - then ` Scrapped: <tool> <id>[, ...].` when something was scrapped.
  - Tool names for reflex actions: Flee→`flee`, AttackEntity→`attack_entity`, Say→`say`, Stop→`stop`, MoveTo→`move_to`.

Behaviour:
- **`startBatch`.** Each entry takes `parsed.prio()`. A lowered call adds the note `<name> <id>: prio_lowered to 2 (prio 3 is only for flee, stop and attack_entity)`. `queue.enqueue` runs with `cancelling = true`, so scrap callbacks don't send a turn early.
- **`reflex()`.** It also enqueues under `cancelling = true`. Then:
  - Idle (`orderFrom == null`), with idle reactions on and not halted: begin an event opening whose text is `<reason> Reflex: <tools>.` (no `INTERRUPT:` prefix).
  - Idle otherwise: stop there; the body acts and no call goes out.
  - In flight: the note goes to `interruptInFlight`.
  - Otherwise: add the note, set `mustReport`, then call `reportBatch()` if the batch isn't reported yet, else `checkProgress()`. The model hears now; unfinished calls report `queued`.
- **`interrupt()` with reflexes on** takes the same report path, with the note `INTERRUPT: <reason>`.
- **`abandon()`** cancels below level 3, so a new order or a finished one leaves a running flee alone. **`stop()` and `bodyGone()`** also call `queue.cancelAll()`, wrapped in `cancelling`.
- **`onFinished` for an unknown call id** (a reflex entry, or one from an abandoned order) still calls `checkProgress()` before it returns. Otherwise a final reply that waits for the queue to go idle would never be posted once a reflex ends. `checkProgress` already returns at once when no order runs. `aReflexRunsBeforeTheModelHearsAndScrapsRoutineWork` pins this with its last assertion.

- [ ] **Step 1: Write the failing tests** in `AgentLoopTest`. The test runner finishes everything at once except `move_to`, and Flee counts as "everything else".

```java
@Test
void aReflexRunsBeforeTheModelHearsAndScrapsRoutineWork() {
    build(b -> {});
    script.add(tools(move("m"), say("s")));
    script.add(text("Safe now."));
    loop.startOrder("F", "walk");
    pump();
    ticks(3); // m is running
    loop.reflex("creeper #9 is 4.0 blocks away, fuse lit.", List.of(new Action.Flee()));
    pump();
    assertEquals(2, requests.size());
    assertEquals(List.of("scrapped", "scrapped"), results(1));
    assertTrue(lastText(1).contains("INTERRUPT: creeper #9 is 4.0 blocks away, fuse lit. Reflex: flee. Scrapped: move_to m, say s."), lastText(1));
    ticks(2);
    assertEquals(List.of("Safe now."), said);
}

@Test
void levelTwoWorkIsPausedAndResumes() {
    build(b -> {});
    script.add(tools(call("m", "move_to", "{\"x\":5,\"y\":64,\"z\":5,\"prio\":2}")));
    script.add(text("Back to it."));
    loop.startOrder("F", "walk");
    pump();
    ticks(3);
    loop.reflex("zombie #7 is 2.0 blocks away.", List.of(new Action.Flee()));
    pump();
    assertEquals(List.of("queued"), results(1));
    assertTrue(lastText(1).contains("Reflex: flee. Paused: move_to m."), lastText(1));
    ticks(10);
    assertEquals("move_to", queue.runningTool(), "m restarted after the flee");
    assertEquals(List.of(), said, "the order waits for m");
}

@Test
void prioThreeOnANonSafetyToolRunsAtTwoAndSaysSo() {
    build(b -> {});
    script.add(tools(call("s", "say", "{\"text\":\"hi\",\"prio\":3}")));
    script.add(text("done"));
    loop.startOrder("F", "greet");
    pump();
    ticks(3);
    assertTrue(lastText(1).contains("say s: prio_lowered to 2"), lastText(1));
}

@Test
void anInterruptWithReflexesOnKeepsTheQueue() {
    build(b -> {});
    script.add(tools(move("m"), say("s")));
    script.add(text("noted"));
    loop.startOrder("F", "walk");
    pump();
    ticks(3);
    loop.interrupt("zombie #7 is 10.0 blocks away.");
    pump();
    assertEquals(2, requests.size());
    assertEquals(List.of("queued", "queued"), results(1));
    assertTrue(lastText(1).contains("INTERRUPT: zombie #7 is 10.0 blocks away."), lastText(1));
    assertEquals("move_to", queue.runningTool());
}

@Test
void aReflexDuringACallVoidsTheRepliesPlan() {
    build(b -> {});
    script.add(tools(say("a"), say("b"), say("c"), say("d"), say("e"), say("f")));
    script.add(tools(say("g"))); // planned before the hit
    script.add(text("ok"));
    loop.startOrder("F", "chatter");
    pump();
    for (int i = 0; i < 100 && brainThread.isEmpty(); i++) {
        queue.tick(); // run the queue without delivering the early-report call
        loop.tick();
    }
    loop.reflex("hit by zombie #7.", List.of(new Action.Flee()));
    pump();
    assertEquals(3, requests.size());
    assertEquals(List.of("cancelled"), results(2), "g never ran");
    assertTrue(lastText(2).contains("INTERRUPT: hit by zombie #7. Reflex: flee."), lastText(2));
}

@Test
void aReflexWhileIdleStartsAReactionThatSaysWhatTheBodyDid() {
    build(b -> {});
    script.add(text("Ran."));
    loop.reflex("creeper #9 is 3.0 blocks away, fuse lit.", List.of(new Action.Flee()));
    pump();
    assertEquals(1, requests.size());
    assertTrue(lastText(0).contains("fuse lit. Reflex: flee."), lastText(0));
}

@Test
void aNewOrderKeepsARunningReflexAndStopEndsIt() {
    build(b -> b.idleReactions = false);
    loop.reflex("zombie #7 is 2.0 blocks away.", List.of(new Action.MoveTo(9, 64, 9)));
    ticks(2);
    assertEquals(0, requests.size());
    script.add(text("hello"));
    loop.startOrder("F", "say hi");
    pump();
    assertEquals("move_to", queue.runningTool(), "the new order didn't cancel the reflex");
    loop.stop();
    assertTrue(queue.idle());
}

@Test
void aReflexAfterStopStillRunsButCallsNoModel() {
    build(b -> {});
    loop.stop();
    loop.reflex("creeper #9 is 3.0 blocks away, fuse lit.", List.of(new Action.MoveTo(9, 64, 9)));
    pump();
    ticks(2);
    assertEquals("move_to", queue.runningTool());
    assertEquals(0, requests.size());
}
```

Change `anInterruptCancelsTheQueueAndReportsAtOnce` to `build(b -> b.reflexes = false)` and keep its assertions. The old behaviour now lives behind the setting, which is a spec change. `anInterruptDuringACallVoidsTheRepliesPlan` stays exactly as it is and must pass unchanged, because the in-flight rule didn't change.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew test --tests "com.example.claudeplayer.core.brain.AgentLoopTest"`
Expected: compilation fails (`reflex`, `reflexes`).

- [ ] **Step 3: Implement** the Settings, Config and AgentLoop changes as listed above. Add a sentence on reflexes and priorities to the AgentLoop class javadoc.

- [ ] **Step 4: Run every core test**

Run: `./gradlew test`
Expected: PASS. The count is 85 plus the tests added in Tasks 1 to 4; read the count from the run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/claudeplayer/core/Settings.java src/main/java/com/example/claudeplayer/Config.java src/main/java/com/example/claudeplayer/core/brain/AgentLoop.java src/test/java/com/example/claudeplayer/core/brain/AgentLoopTest.java
git commit -m "Loop: reflexes enqueue at level 3 and report what was paused or scrapped; interrupts keep the queue"
```

---

### Task 5: Game-side wiring and docs

There are no unit tests here (game code). The gate is a green `./gradlew build` plus F's in-game checks.

**Files:**
- Modify: `src/main/java/com/example/claudeplayer/body/SnapshotBuilder.java`
- Modify: `src/main/java/com/example/claudeplayer/nav/EscapePlanner.java`
- Modify: `src/main/java/com/example/claudeplayer/body/ActionExecutor.java`
- Modify: `src/main/java/com/example/claudeplayer/nav/MoveHandle.java`
- Modify: `src/main/java/com/example/claudeplayer/event/DamageWatcher.java`
- Modify: `src/main/java/com/example/claudeplayer/AgentRuntime.java:128-133`
- Modify: `docs/claude-player-modmap.md`, `docs/claude-player-handoff.md`, `docs/superpowers/specs/2026-10-22-claude-code-bridge-design.md`

**Interfaces:**
- Consumes:
  - Task 3: `Reflexes.decide`, `Reflexes.hazard`, `Reflexes.horizontal`.
  - Task 4: `AgentLoop.reflex`, `AgentLoop.interrupt`, `Settings.reflexes()`.
  - Task 2: `Action.Flee`.
- Produces:
  - `SnapshotBuilder.hostilesXZ(ServerPlayer body, double range)`: hostiles within `range` on XZ at any height, nearest first by XZ. The search box is `inflate(range, 384, range)`.
  - `SnapshotBuilder.litCreeper(Entity e)`: `e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited())`.
  - `EscapePlanner.destination(ServerPlayer body, List<? extends Entity> threats)`: a `@Nullable Vec3`. `escapeExists` becomes `threats.isEmpty() || destination(..) != null`.
  - `ActionExecutor.ATTACK_REACH`, made `public static final`.

- [ ] **Step 1: Wire it**
  - **EscapePlanner.destination.** Probe the same 8 directions and spot rules as today. Start from the direction pointing directly away from the nearest threat, then take ±45°, ±90°, ±135° and 180° in that order. Return the first reachable spot.
  - **ActionExecutor, `Flee`.**
    - Threats are `hostilesXZ(p, hostileRange)`.
    - None → `ActionResult.ok("no_threats")`.
    - No destination → `failed("no_escape")`.
    - Otherwise `rt.navigator().moveTo(p, dest)`.
  - **MoveHandle, hazard check.** It runs each tick, before `Navigator.steer`.
    - Probe point: the body's position plus 0.8 blocks along the horizontal direction to the steer point. Skip the check when that direction is under 0.01 long.
    - feet = `BlockPos.containing(probe)`.
    - dropDepth = consecutive blocks below feet with an empty collision shape and no fluid, at most 24.
    - landsInWater = the first block that stops the count holds water.
    - Ids come from `SnapshotBuilder.blockId`. Call `Reflexes.hazard(.., body.getHealth())`.
    - On a hit: `Navigator.halt(body)` and return `failed("hazard_" + kind)`.
  - **DamageWatcher.**
    - `SCAN_TICKS` becomes 2.
    - Add `fuses = new ThreatTracker(0, 60)`.
    - `pending` becomes `record Pending(String reason, List<Action> actions)`.
    - The scan walks `hostilesXZ`. A lit creeper reports to `fuses` with key `lit:<id>`, and its reason adds ", fuse lit". Any other mob reports to `threats` with key `near:<id>`.
    - `onHurt` with an attacker builds the same way; without one, it gets no actions.
    - Actions come from `Reflexes.decide(level, situation, lit, rt.settings().toolPhase() >= 4, ActionExecutor.ATTACK_REACH)`: FLEE→`[new Action.Flee()]`, FIGHT→`[new Action.AttackEntity(id)]`, NONE→`[]`.
    - Delivery: `rt.loop().reflex(..)` when actions are non-empty and `settings.reflexes()`; otherwise `rt.loop().interrupt(..)`.
    - Log at INFO: `reflex {} for {} #{}`, with the move, type and id.
    - The existing help call stays in `onHurt`.
  - **AgentRuntime.tick.** The order becomes `body.tick(); damage.tick(); queue.tick(); loop.tick();`, with the comment "reflexes enqueue before the queue ticks, so a level-3 action starts this tick".

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, with PurityTest green and the same test count as Task 4.

- [ ] **Step 3: Docs**
  - **modmap**
    - LAYOUT: add a `core/safety/Reflexes.java` row ("Pure: fight or flee by caution level, lit creepers, lava, fire and deadly drops"), and update the ActionQueue and EscapePlanner rows.
    - TOOLS: add `flee` in phase 1, `prio` on every tool, and the `scrapped` code.
    - KEYS: add a `reflexes` row beside `idle_reactions`.
    - SYSTEMS: update the lines on interrupts (`grep -n -i interrupt docs/claude-player-modmap.md`).
    - GAME FACTS: add `COMPILED: Creeper#getSwellDir, Creeper#isIgnited; hazard probe 0.8 blocks ahead`.
    - STATUS: add a line for piece 1.
  - **handoff:** add F's piece-1 checks (M1 to M5 below) to "Still to do".
  - **spec section 6:** apply the five amendments at the top of this plan.

- [ ] **Step 4: Build again**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/claudeplayer docs
git commit -m "Reflexes in game: XZ threat scan every 2 ticks, flee to the side away from the threat, moves refuse lava, fire and deadly drops"
```

**In-game checks for F** (`./gradlew runClient -Pquickstart="Claude Test"`; paste the `reflex` log lines into the handoff):
- **M1.** At caution level 4 or 5, spawn a zombie 10 blocks away. The body starts fleeing before Claude's chat reply, and the log shows `reflex FLEE`.
- **M2.** At level 0, ignite a creeper near the body (flint and steel). It flees.
- **M3.** `claude: walk to <a spot past a lava pool>`. The body halts at the edge, and the move reports `failed:hazard_lava`.
- **M4.** Give an order with routine steps, then spawn a zombie. The routine steps come back `scrapped`, and a prio-2 move resumes afterwards.
- **M5.** `claude: stop` while it flees. It halts within a second.

**Known consequence (F's XZ call):** a hostile in a cave far below still counts, so the body may take a short flee. It is bounded by the 30 s re-arm per mob.

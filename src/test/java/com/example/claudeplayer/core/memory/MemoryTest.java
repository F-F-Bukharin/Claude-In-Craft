package com.example.claudeplayer.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryTest {
    private long now = 1_000_000;

    /** In-memory store that records every save. */
    private static final class FakeStore implements Memory.Store {
        final List<Memory.Entry> initial = new ArrayList<>();
        List<Memory.Entry> saved;

        @Override
        public List<Memory.Entry> load() {
            return initial;
        }

        @Override
        public void save(List<Memory.Entry> entries) {
            saved = entries;
        }
    }

    private Memory memory(FakeStore store, int shortCapacity, int longCapacity) {
        return new Memory(Settings.of(b -> {
            b.shortTermCapacity = shortCapacity;
            b.shortTermMinutes = 10;
            b.longTermCapacity = longCapacity;
        }), store, () -> now);
    }

    @Test
    void shortTermReplacesBySubjectFadesAndEvicts() {
        Memory m = memory(new FakeStore(), 2, 10);
        m.note("Chest@1,2,3", "15 oak_log, 17 cobblestone, 64 torch");
        m.note("chest@1,2,3", "14 oak_log, 17 cobblestone, 64 torch");
        assertEquals(1, m.shortTerm().size(), "same subject, any case, replaces");
        assertEquals("14 oak_log, 17 cobblestone, 64 torch", m.shortTerm().get(0).text());
        m.note("b", "2");
        m.note("c", "3");
        assertEquals(List.of("b", "c"), m.shortTerm().stream().map(Memory.Entry::subject).toList(), "oldest evicted");
        now += 11 * 60_000;
        assertTrue(m.shortTerm().isEmpty(), "notes fade");
    }

    @Test
    void longTermPersistsRefusesWhenFullAndForgets() {
        FakeStore store = new FakeStore();
        store.initial.add(new Memory.Entry("home", "base at 0,64,0", 1));
        Memory m = memory(store, 5, 2);
        assertEquals(1, m.longTerm().size(), "loaded from the store");
        assertTrue(m.remember("chest@1,2,3", "In this chest I stored building materials"));
        assertEquals(2, store.saved.size(), "every change is saved");
        assertFalse(m.remember("new", "no room"), "full: refuse new subjects");
        assertTrue(m.remember("chest@1,2,3", "Building materials and torches"), "updating an existing subject still works");
        m.note("home", "standing at home");
        assertTrue(m.forget("HOME"));
        assertTrue(m.shortTerm().isEmpty() && m.longTerm().size() == 1, "forget clears both memories");
        assertFalse(m.forget("nothing"));
    }

    @Test
    void renderShowsBothKindsAsCleanData() {
        Memory m = memory(new FakeStore(), 5, 5);
        assertEquals("", m.render());
        m.remember("chest@1,2,3", "In this chest I stored <b>XYZ</b> items");
        m.note("container@1,2,3", "chest at 1, 2, 3: 15 X, 17 Y, 64 Z");
        now += 3 * 60_000;
        String block = m.render();
        assertTrue(block.startsWith("<memory>\nLong-term"), block);
        assertTrue(block.contains("chest@1,2,3: In this chest I stored ‹b›XYZ‹/b› items"), block);
        assertTrue(block.contains("container@1,2,3 (3 min ago): chest at 1, 2, 3: 15 X, 17 Y, 64 Z"), block);
        assertTrue(block.endsWith("</memory>"), block);
    }

    @Test
    void memoryToolsRunInTheCoreAndTheRestReachesTheWorld() {
        Memory m = memory(new FakeStore(), 5, 1);
        List<Action> world = new ArrayList<>();
        ActionRunner runner = new MemoryActions(m, action -> {
            world.add(action);
            return ActionRunner.Handle.done(ActionResult.ok());
        });
        assertEquals("ok", runner.start(new Action.Note("a", "1")).poll().code());
        assertEquals("ok", runner.start(new Action.Remember("b", "2")).poll().code());
        assertEquals("failed:memory_full", runner.start(new Action.Remember("c", "3")).poll().code());
        assertEquals("ok", runner.start(new Action.Forget("a")).poll().code());
        assertEquals("failed:not_found", runner.start(new Action.Forget("a")).poll().code());
        runner.start(new Action.Say("hi"));
        assertEquals(List.of(new Action.Say("hi")), world);
    }

    @Test
    void jsonFileRoundTrips(@TempDir Path dir) {
        JsonMemoryFile file = new JsonMemoryFile(dir.resolve("world").resolve("claudeplayer").resolve("memory.json"));
        assertTrue(file.load().isEmpty());
        file.save(List.of(new Memory.Entry("chest@1,2,3", "building materials", 42)));
        assertEquals(List.of(new Memory.Entry("chest@1,2,3", "building materials", 42)), file.load());
    }
}

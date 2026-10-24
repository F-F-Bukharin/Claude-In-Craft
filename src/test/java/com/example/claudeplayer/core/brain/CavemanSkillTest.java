package com.example.claudeplayer.core.brain;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Caveman rules loaded from a SKILL.md file: front matter, newest match, override path, cap and hash. */
class CavemanSkillTest {
    private static final String FRONT = "---\nname: caveman\ndescription: talk like caveman\n---\n";

    @TempDir
    Path tmp;

    @Test
    void frontMatterIsStrippedAndSourceKept() throws IOException {
        Path root = tmp.resolve("skills");
        Path file = write(root.resolve("caveman/SKILL.md"), FRONT + "\nYou talk short.\n\nNo filler.\n");

        CavemanSkill skill = CavemanSkill.load(null, root).orElseThrow();

        assertEquals("You talk short.\n\nNo filler.", skill.rules());
        assertEquals(file, skill.source());
    }

    @Test
    void noFrontMatterKeepsWholeFile() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), "  Me caveman.\nNo words big.  \n");

        CavemanSkill skill = CavemanSkill.load(null, root).orElseThrow();

        assertEquals("Me caveman.\nNo words big.", skill.rules());
    }

    @Test
    void newestCavemanSkillWins() throws IOException {
        Path root = tmp.resolve("skills");
        Path older = write(root.resolve("caveman-old/SKILL.md"), FRONT + "old rules");
        Path newer = write(root.resolve("caveman-new/SKILL.md"), FRONT + "new rules");
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-06-01T00:00:00Z")));

        CavemanSkill skill = CavemanSkill.load(null, root).orElseThrow();

        assertEquals("new rules", skill.rules());
        assertEquals(newer, skill.source());
    }

    @Test
    void nonCavemanSkillIsIgnored() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("pdf/SKILL.md"), "---\nname: pdf\n---\nread pdfs");

        assertFalse(CavemanSkill.load(null, root).isPresent());
    }

    @Test
    void matchesFrontMatterNameInDirectoryNotNamedCaveman() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("plugin-x/hat/SKILL.md"), "---\nname: Caveman Lite\n---\nlite rules");

        CavemanSkill skill = CavemanSkill.load(null, root).orElseThrow();

        assertEquals("lite rules", skill.rules());
    }

    @Test
    void nameOutsideFrontMatterDoesNotMatch() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("other/SKILL.md"), "---\nname: pdf\n---\nname: caveman in the body");

        assertFalse(CavemanSkill.load(null, root).isPresent());
    }

    @Test
    void searchStopsAtFourLevels() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("a/b/c/d/SKILL.md"), FRONT + "too deep");
        assertFalse(CavemanSkill.load(null, root).isPresent());

        write(root.resolve("a/b/c/SKILL.md"), FRONT + "just deep enough");
        assertEquals("just deep enough", CavemanSkill.load(null, root).orElseThrow().rules());
    }

    @Test
    void overridePathWinsOverSearch() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "installed rules");
        Path override = write(tmp.resolve("mine/rules.md"), "override rules");

        CavemanSkill skill = CavemanSkill.load(override.toString(), root).orElseThrow();

        assertEquals("override rules", skill.rules());
        assertEquals(override, skill.source());
    }

    @Test
    void missingOverrideIsEmptyEvenWhenSkillIsInstalled() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "installed rules");

        assertFalse(CavemanSkill.load(tmp.resolve("nope.md").toString(), root).isPresent());
    }

    @Test
    void unreadableOverrideIsEmpty() {
        assertFalse(CavemanSkill.load(tmp.toString(), tmp.resolve("skills")).isPresent());
    }

    @Test
    void blankOverrideFallsBackToSearch() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "installed rules");

        assertEquals("installed rules", CavemanSkill.load("   ", root).orElseThrow().rules());
    }

    @Test
    void capsAtMaxBytesOnCharacterBoundary() throws IOException {
        Path root = tmp.resolve("skills");
        String body = "a".repeat(CavemanSkill.MAX_BYTES - 1) + "é";
        write(root.resolve("caveman/SKILL.md"), FRONT + body);

        String rules = CavemanSkill.load(null, root).orElseThrow().rules();

        assertEquals("a".repeat(CavemanSkill.MAX_BYTES - 1), rules);
        assertTrue(rules.getBytes(UTF_8).length <= CavemanSkill.MAX_BYTES);
    }

    @Test
    void capDropsSplitEmojiAndKeepsExactFit() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "a".repeat(CavemanSkill.MAX_BYTES - 2) + "🦴");
        assertEquals("a".repeat(CavemanSkill.MAX_BYTES - 2), CavemanSkill.load(null, root).orElseThrow().rules());

        String fits = "a".repeat(CavemanSkill.MAX_BYTES - 4) + "🦴";
        write(root.resolve("caveman/SKILL.md"), FRONT + fits);
        assertEquals(fits, CavemanSkill.load(null, root).orElseThrow().rules());
    }

    @Test
    void capStripsTrailingSpaceLeftByTheCut() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "a".repeat(CavemanSkill.MAX_BYTES - 1) + " é");

        assertEquals("a".repeat(CavemanSkill.MAX_BYTES - 1), CavemanSkill.load(null, root).orElseThrow().rules());
    }

    @Test
    void sha256IsLowercaseHexOfRulesBytes() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "abc");

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CavemanSkill.load(null, root).orElseThrow().sha256());
    }

    @Test
    void blankRulesAreEmpty() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "\n   \n");

        assertFalse(CavemanSkill.load(null, root).isPresent());
    }

    @Test
    void missingSkillsDirIsEmpty() {
        assertFalse(CavemanSkill.load(null, tmp.resolve("no-such-dir")).isPresent());
    }

    @Test
    void promptSectionAndNoteWrapRules() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("caveman/SKILL.md"), FRONT + "Few words.");

        CavemanSkill skill = CavemanSkill.load(null, root).orElseThrow();

        assertEquals("\n\n# Caveman\nFew words.", skill.promptSection());
        assertEquals("Caveman rules from now on (usage is high):\nFew words.", skill.note());
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, UTF_8);
        return file;
    }
}

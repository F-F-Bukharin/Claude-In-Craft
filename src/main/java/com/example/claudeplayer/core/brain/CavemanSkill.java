package com.example.claudeplayer.core.brain;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The caveman rules: the body of the newest caveman SKILL.md under the skills folder (or an explicit override file),
 * with the YAML front matter removed and the text capped at {@link #MAX_BYTES}.
 */
public record CavemanSkill(String rules, String sha256, Path source) {
    private static final Logger LOG = LoggerFactory.getLogger(CavemanSkill.class);

    /** The rules are capped at this many UTF-8 bytes. */
    public static final int MAX_BYTES = 8 * 1024;

    /** How many path levels below the skills folder the search looks. */
    private static final int MAX_DEPTH = 4;

    /**
     * Loads the caveman rules. A non-blank {@code overridePath} is the only file read; otherwise the newest caveman
     * SKILL.md under {@code skillsRoot} is used. Missing, unreadable or blank rules give empty, never an exception.
     */
    public static Optional<CavemanSkill> load(String overridePath, Path skillsRoot) {
        try {
            if (overridePath != null && !overridePath.isBlank()) {
                return fromFile(Path.of(overridePath.strip()));
            }
            if (skillsRoot == null) {
                return Optional.empty();
            }
            Optional<Path> newest = newest(skillsRoot);
            return newest.isPresent() ? fromFile(newest.get()) : Optional.empty();
        } catch (IOException | UncheckedIOException | InvalidPathException e) {
            LOG.warn("Caveman skill not loaded: {}", e.toString());
            return Optional.empty();
        }
    }

    /** The text appended to the end of the system prompt file. */
    public String promptSection() {
        return "\n\n# Caveman\n" + rules;
    }

    /** The one note sent for an order that is already running. */
    public String note() {
        return "Caveman rules from now on (usage is high):\n" + rules;
    }

    private static Optional<CavemanSkill> fromFile(Path file) throws IOException {
        String rules = cap(body(Files.readString(file, UTF_8)).strip()).strip();
        if (rules.isBlank()) {
            return Optional.empty();
        }
        String sha = sha256(rules);
        LOG.info("Caveman skill loaded from {} (sha256 {})", file, sha);
        return Optional.of(new CavemanSkill(rules, sha, file));
    }

    /** The newest file under {@code root} that is a caveman skill, or empty. Ties go to the first path. */
    private static Optional<Path> newest(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .sorted()
                    .toList();
            Path best = null;
            FileTime bestTime = null;
            for (Path p : files) {
                if (!isCaveman(p)) {
                    continue;
                }
                FileTime time = Files.getLastModifiedTime(p);
                if (best == null || time.compareTo(bestTime) > 0) {
                    best = p;
                    bestTime = time;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    /** A caveman skill has "caveman" in its folder name, or in a {@code name:} line of its front matter. */
    private static boolean isCaveman(Path file) {
        Path dir = file.getParent();
        if (dir != null && dir.getFileName() != null
                && dir.getFileName().toString().toLowerCase(Locale.ROOT).contains("caveman")) {
            return true;
        }
        try {
            List<String> lines = Files.readString(file, UTF_8).lines().toList();
            int end = frontMatterEnd(lines);
            for (int i = 1; i < end; i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("name:")
                        && line.substring("name:".length()).toLowerCase(Locale.ROOT).contains("caveman")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** The text after the front matter, with the lines joined by {@code \n}. No front matter gives the whole text. */
    private static String body(String text) {
        List<String> lines = text.lines().toList();
        int end = frontMatterEnd(lines);
        if (end < 0) {
            return text;
        }
        return String.join("\n", lines.subList(end + 1, lines.size()));
    }

    /** Index of the closing {@code ---} line when the first line opens a front matter block, else -1. */
    private static int frontMatterEnd(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return -1;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    /** Cuts the text to {@link #MAX_BYTES} UTF-8 bytes, backing up so a character is never split. */
    private static String cap(String text) {
        byte[] bytes = text.getBytes(UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return text;
        }
        int end = MAX_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, UTF_8);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}

package com.example.claudeplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The core must stay pure Java, so it stays unit-testable without Minecraft. */
class PurityTest {
    private static final Path CORE = Path.of("src/main/java/com/example/claudeplayer/core");
    private static final Pattern GAME_IMPORT = Pattern.compile("^import (static )?(net\\.minecraft|net\\.minecraftforge|com\\.mojang)\\b.*");

    @Test
    void coreHasNoGameImports() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORE)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty(), "no core sources found under " + CORE.toAbsolutePath());
        List<String> offenders = files.stream()
                .flatMap(file -> lines(file).filter(line -> GAME_IMPORT.matcher(line).matches()).map(line -> CORE.relativize(file) + ": " + line))
                .toList();
        assertEquals(List.of(), offenders);
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

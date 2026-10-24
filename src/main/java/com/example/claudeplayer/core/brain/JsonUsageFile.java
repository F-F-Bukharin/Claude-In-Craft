package com.example.claudeplayer.core.brain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The usage ledger's state as a readable JSON file beside the mod's config.
 * Failures are logged, never thrown: losing a usage write must not take the server down.
 */
public final class JsonUsageFile implements UsageLedger.Store {
    private static final Logger LOG = LoggerFactory.getLogger(JsonUsageFile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;

    public JsonUsageFile(Path file) {
        this.file = file;
    }

    @Override
    public JsonObject load() {
        if (!Files.isRegularFile(file)) {
            LOG.info("No usage state at {} yet; starting empty", file);
            return new JsonObject();
        }
        try {
            JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (el.isJsonObject()) return el.getAsJsonObject();
            LOG.warn("Usage state in {} isn't a JSON object; starting empty", file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't read usage state from {}; starting empty", file, e);
        }
        return new JsonObject();
    }

    @Override
    public void save(JsonObject state) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Couldn't save usage state to {}", file, e);
        }
    }
}

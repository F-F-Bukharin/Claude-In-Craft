package com.example.claudeplayer.core.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-term memory as a readable JSON file (one per world), so F can inspect or edit what Claude remembers.
 * Failures are logged, never thrown: losing a memory write must not take the server down.
 */
public final class JsonMemoryFile implements Memory.Store {
    private static final Logger LOG = LoggerFactory.getLogger(JsonMemoryFile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;

    public JsonMemoryFile(Path file) {
        this.file = file;
    }

    @Override
    public List<Memory.Entry> load() {
        List<Memory.Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) return entries;
        try {
            for (JsonElement el : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                entries.add(new Memory.Entry(o.get("subject").getAsString(), o.get("text").getAsString(),
                        o.has("updated") ? o.get("updated").getAsLong() : 0));
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("Couldn't read long-term memory from {}; starting empty", file, e);
        }
        return entries;
    }

    @Override
    public void save(List<Memory.Entry> entries) {
        JsonArray array = new JsonArray();
        for (Memory.Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("subject", e.subject());
            o.addProperty("text", e.text());
            o.addProperty("updated", e.updatedMillis());
            array.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(array), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Couldn't save long-term memory to {}", file, e);
        }
    }
}

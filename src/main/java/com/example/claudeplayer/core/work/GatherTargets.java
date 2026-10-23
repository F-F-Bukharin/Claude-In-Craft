package com.example.claudeplayer.core.work;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What gather may mine: a block id, or a group name that stands for several blocks. Ids are short ids
 * ("oak_log", the way SnapshotBuilder.blockId writes them); a modded block keeps its namespace. Whether a single id
 * names a real block is the game side's question.
 */
public final class GatherTargets {
    private static final Pattern ID = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final Map<String, Set<String>> GROUPS = Map.ofEntries(
            Map.entry("stone", Set.of("stone", "deepslate", "andesite", "diorite", "granite", "tuff")),
            Map.entry("coal", ores("coal")),
            Map.entry("iron", ores("iron")),
            Map.entry("copper", ores("copper")),
            Map.entry("gold", ores("gold")),
            Map.entry("diamond", ores("diamond")),
            Map.entry("redstone", ores("redstone")),
            Map.entry("lapis", ores("lapis")),
            Map.entry("emerald", ores("emerald")),
            Map.entry("sand", Set.of("sand", "red_sand")),
            Map.entry("dirt", Set.of("dirt", "grass_block", "podzol", "mycelium")), // all of them drop dirt
            Map.entry("gravel", Set.of("gravel")),
            Map.entry("clay", Set.of("clay")));

    private GatherTargets() {}

    /** {@code group}: a group name rather than one id, so the game side needn't look it up in the block registry. */
    public record Target(String name, boolean group, Predicate<String> matches) {}

    /** Lower case, without the minecraft: prefix. */
    public static String normalize(String raw) {
        String id = raw.strip().toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** The target for this text, or null when it is neither a group nor shaped like a block id. */
    public static Target resolve(String raw) {
        String name = normalize(raw);
        if (name.equals("log") || name.equals("logs") || name.equals("wood")) return new Target("log", true, GatherTargets::isLog);
        Set<String> ids = GROUPS.get(name);
        if (ids != null) return new Target(name, true, ids::contains);
        if (!ID.matcher(name).matches()) return null;
        return new Target(name, false, name::equals);
    }

    /**
     * Any tree trunk: every *_log (modded ones too) and the two nether stems. Not other *_stem blocks (melon and
     * pumpkin plants, mushroom stems), and never stripped logs, which are worked wood.
     */
    static boolean isLog(String id) {
        return (id.endsWith("_log") || id.equals("crimson_stem") || id.equals("warped_stem")) && !id.contains("stripped_");
    }

    private static Set<String> ores(String kind) {
        return Set.of(kind + "_ore", "deepslate_" + kind + "_ore");
    }
}

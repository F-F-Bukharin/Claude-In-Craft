package com.example.claudeplayer.core.safety;

import com.example.claudeplayer.core.work.GatherRule;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The hunt routine's rules, from plain facts. CautionPolicy only allows hostiles, so hunting has its own gate: only a
 * cow, pig, sheep, chicken or rabbit, only of the kind asked, and never a player, villager, wandering trader, iron
 * golem, or an animal that is named, tamed, leashed, ridden, saddled or a baby. The routine asks before every swing, because an animal
 * can be named, leashed or tamed while it is being chased.
 */
public final class HuntRules {
    private HuntRules() {}

    /** The only animals a hunt may touch. */
    public static final Set<String> HUNTABLE = Set.of("cow", "pig", "sheep", "chicken", "rabbit");
    /** Blocks around the body in which an animal is looked for. */
    public static final int SEARCH_RANGE = 24;
    /** 120 s for the whole hunt. */
    public static final int LIMIT_TICKS = 2400;
    /** The routine's own limit ends the hunt with a report first; the runner's timeout is this backstop. */
    public static final int BACKSTOP_TICKS = LIMIT_TICKS + 200;
    /** 20 s on one animal that keeps getting away, then it is left alone. */
    public static final int CHASE_TICKS = 400;
    /** 3 s walking onto the drops after a kill. */
    public static final int DROP_TICKS = 60;
    /** Blocks around the kill spot in which drops are picked up. */
    public static final double DROP_RANGE = 4.0;
    /** A hostile this close (blocks) ends the hunt, as in gather. */
    public static final double DANGER_RANGE = GatherRule.DANGER_RANGE;
    /** Health below this fraction of the maximum ends the hunt, as in gather. */
    public static final double LOW_HEALTH = GatherRule.LOW_HEALTH;

    /**
     * What the game side knows about a candidate. {@code typeId} is "cow" or "minecraft:cow". {@code ridden}: it carries
     * a passenger, or wears a saddle.
     */
    public record Facts(String typeId, boolean player, boolean villager, boolean ironGolem, boolean named, boolean tamed,
                        boolean leashed, boolean baby, boolean ridden) {}

    /**
     * Why the candidate may not be hit, or null when it may: player, villager (also the wandering trader), iron_golem,
     * not_huntable (a type outside the five, or an asked kind outside them), wrong_kind, named, tamed, leashed, ridden
     * (a passenger or a saddle), baby.
     */
    public static String refusal(String asked, Facts f) {
        if (f.player()) return "player";
        if (f.villager()) return "villager";
        if (f.ironGolem()) return "iron_golem";
        String kind = shortId(f.typeId());
        if (!HUNTABLE.contains(kind) || !HUNTABLE.contains(shortId(asked))) return "not_huntable";
        if (!kind.equals(shortId(asked))) return "wrong_kind";
        if (f.named()) return "named";
        if (f.tamed()) return "tamed";
        if (f.leashed()) return "leashed";
        if (f.ridden()) return "ridden";
        if (f.baby()) return "baby";
        return null;
    }

    public static boolean allowed(String asked, Facts f) {
        return refusal(asked, f) == null;
    }

    /**
     * danger (a hostile within DANGER_RANGE, or health under LOW_HEALTH) or timeout; null to keep going. Done is not
     * here: the routine still picks up the last kill's drops first ({@link #done}). {@code nearestHostile} is in
     * blocks (infinity when none), {@code health} a fraction of the maximum.
     */
    public static String stop(int ticks, double nearestHostile, double health) {
        if (nearestHostile <= DANGER_RANGE || health < LOW_HEALTH) return "danger";
        if (ticks >= LIMIT_TICKS) return "timeout";
        return null;
    }

    public static boolean done(int kills, int count) {
        return kills >= count;
    }

    /**
     * "killed 2 cow; drops: 3 beef, 1 leather", plus "; stopped: danger" when it did not finish by itself ({@code stop}
     * null or "done" adds nothing). {@code drops}: item id to count, in the order to list them.
     */
    public static String summary(String animal, int kills, Map<String, Integer> drops, String stop) {
        String items = drops.isEmpty() ? "none" : drops.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        String end = stop == null || stop.equals("done") ? "" : "; stopped: " + stop;
        return "killed " + kills + " " + animal + "; drops: " + items + end;
    }

    private static String shortId(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1);
    }
}

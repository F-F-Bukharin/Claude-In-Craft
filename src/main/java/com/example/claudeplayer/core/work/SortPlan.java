package com.example.claudeplayer.core.work;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * sort_chests' planner, pure. Each container gets at most one kind; each kind gets as few containers as hold it,
 * starting with the containers whose majority (by slots) is already that kind; then the moves that bring every
 * movable item into a container of its kind. Capacity is counted per slot as the game fills it: onto matching
 * stacks up to their max first, then empty slots, so nothing ever overfills. The moves assume every item that
 * leaves a container is taken out before anything is put in (the body carries them), which is what lets two full
 * containers swap. What can't fit stays where it is. A kind with no container left keeps its items in place.
 */
public final class SortPlan {
    /** sort_chests' ok text stays at most this long. */
    public static final int SUMMARY_MAX = 600;
    private static final int MAX_ROUNDS = 64;
    private static final Set<String> MATERIAL_IDS = Set.of("coal", "charcoal", "diamond", "emerald", "lapis_lazuli", "redstone",
            "quartz", "amethyst_shard", "netherite_scrap", "ancient_debris");

    private SortPlan() {
    }

    /** The kinds, in tie-break order; the labels are the tool description's words. */
    public enum Kind {
        BLOCKS("blocks"), MATERIALS("ores and ingots"), FOOD("food"), GEAR("tools and weapons"), OTHER("the rest");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One non-empty slot: {@code maxStack} is the most one slot holds (1 for tools and armor). */
    public record Stack(String item, int count, int maxStack, Kind kind) {}

    /** A container: its slot count and its non-empty slots in slot order. */
    public record Box(int slots, List<Stack> stacks) {
        public Box {
            stacks = List.copyOf(stacks);
        }
    }

    /** {@code count} of {@code item} from box {@code from} to box {@code to} (indexes in the input list). */
    public record Move(int from, int to, String item, int count) {}

    /** The kind each box keeps (null: a spare box) and the moves, kind by kind in Kind order. */
    public record Plan(List<Kind> kinds, List<Move> moves) {
        public int items() {
            return moves.stream().mapToInt(Move::count).sum();
        }
    }

    public static Plan plan(List<Box> boxes, Set<String> fixed) {
        int n = boxes.size();
        Map<String, Stack> info = new HashMap<>(); // kind and max stack per item, from its first slot
        Map<String, Integer> total = new LinkedHashMap<>();
        List<Map<String, Integer>> amount = new ArrayList<>();
        for (Box box : boxes) {
            Map<String, Integer> here = new LinkedHashMap<>();
            for (Stack s : box.stacks()) {
                info.putIfAbsent(s.item(), s);
                here.merge(s.item(), s.count(), Integer::sum);
                total.merge(s.item(), s.count(), Integer::sum);
            }
            amount.add(here);
        }
        Map<Kind, Integer> need = new EnumMap<>(Kind.class);
        total.forEach((item, count) -> need.merge(info.get(item).kind(), ceilDiv(count, info.get(item).maxStack()), Integer::sum));

        Map<Kind, List<Integer>> targets = new EnumMap<>(Kind.class);
        Kind[] assigned = assign(boxes, need, targets);

        // How much of each item stays in its box: everything that can't move, and later what doesn't fit.
        List<Map<String, Integer>> stay = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            Map<String, Integer> s = new HashMap<>();
            for (Map.Entry<String, Integer> e : amount.get(b).entrySet()) {
                s.put(e.getKey(), movable(b, e.getKey(), info, fixed, assigned, targets) ? 0 : e.getValue());
            }
            stay.add(s);
        }
        // Leftovers only grow (less room where they stay), so this settles; the last round is consistent.
        List<Move> moves = List.of();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            List<Move> out = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                List<Integer> to = targets.get(kind);
                if (to == null) continue;
                List<Sim> sims = new ArrayList<>();
                for (int t : to) {
                    Sim sim = new Sim(boxes.get(t));
                    for (Map.Entry<String, Integer> e : amount.get(t).entrySet()) {
                        int leaving = e.getValue() - stay.get(t).get(e.getKey());
                        if (leaving > 0) sim.remove(e.getKey(), leaving);
                    }
                    sims.add(sim);
                }
                for (int b = 0; b < n; b++) {
                    if (assigned[b] == kind) continue;
                    for (Map.Entry<String, Integer> e : amount.get(b).entrySet()) {
                        String item = e.getKey();
                        if (info.get(item).kind() != kind || !movable(b, item, info, fixed, assigned, targets)) continue;
                        int left = e.getValue();
                        for (int i = 0; i < to.size() && left > 0; i++) {
                            int put = sims.get(i).insert(item, left, info.get(item));
                            if (put > 0) out.add(new Move(b, to.get(i), item, put));
                            left -= put;
                        }
                        if (left != stay.get(b).get(item)) {
                            stay.get(b).put(item, left);
                            changed = true;
                        }
                    }
                }
            }
            moves = out;
            if (!changed) break;
        }
        return new Plan(Collections.unmodifiableList(Arrays.asList(assigned)), List.copyOf(moves));
    }

    /** The kind filling the most slots of the box (ties to the first kind), or null when it is empty. */
    public static Kind majority(Box box) {
        int[] slots = new int[Kind.values().length];
        for (Stack s : box.stacks()) slots[s.kind().ordinal()]++;
        Kind best = null;
        for (Kind k : Kind.values()) {
            if (slots[k.ordinal()] > 0 && (best == null || slots[k.ordinal()] > slots[best.ordinal()])) best = k;
        }
        return best;
    }

    /**
     * The boxes after the moves: every removal first, then the inserts in order, as the game fills slots.
     * Throws IllegalStateException if a move takes what isn't there or doesn't fit.
     */
    public static List<Box> apply(List<Box> boxes, List<Move> moves) {
        Map<String, Stack> info = new HashMap<>();
        for (Box box : boxes) for (Stack s : box.stacks()) info.putIfAbsent(s.item(), s);
        List<Sim> sims = boxes.stream().map(Sim::new).toList();
        for (Move m : moves) {
            if (sims.get(m.from()).remove(m.item(), m.count()) != m.count()) throw new IllegalStateException("not there: " + m);
        }
        for (Move m : moves) {
            if (sims.get(m.to()).insert(m.item(), m.count(), info.get(m.item())) != m.count()) throw new IllegalStateException("doesn't fit: " + m);
        }
        return sims.stream().map(Sim::box).toList();
    }

    /** Ores, raw ores, ingots, nuggets and gems by id; the game side also checks the Forge tags. */
    public static boolean isMaterial(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return path.endsWith("_ingot") || path.endsWith("_nugget") || path.endsWith("_ore") || path.startsWith("raw_")
                || MATERIAL_IDS.contains(path);
    }

    /**
     * "sorted 3 containers: moved 40 items in 4 moves; chest at 1, 64, 2: blocks; ..." then {@code tail}, at most
     * {@link #SUMMARY_MAX} characters: the container list is cut with "+N more", the tail never.
     */
    public static String summary(List<String> names, List<Kind> kinds, int items, int moves, String tail) {
        int n = names.size();
        StringBuilder sb = new StringBuilder(moves == 0 ? "already sorted: " + n + " containers"
                : "sorted " + n + " containers: moved " + items + (items == 1 ? " item" : " items") + " in " + moves + (moves == 1 ? " move" : " moves"));
        String end = tail == null || tail.isEmpty() ? "" : "; " + tail;
        for (int i = 0; i < n; i++) {
            Kind k = kinds.get(i);
            String part = "; " + names.get(i) + ": " + (k == null ? "spare" : k.label());
            String reserve = i < n - 1 ? "; +" + (n - i - 1) + " more" : "";
            if (sb.length() + part.length() + reserve.length() + end.length() > SUMMARY_MAX) {
                sb.append("; +").append(n - i).append(" more");
                break;
            }
            sb.append(part);
        }
        return sb.append(end).toString();
    }

    /**
     * Kinds by need (slots), largest first, ties in Kind order. Each takes boxes until their slots cover its need:
     * first those whose majority is the kind, then boxes no later kind wants (empty, or a kind already placed),
     * then the rest. Within a tier a box that covers what is left alone wins (the one already holding most of the
     * kind), else the box holding most of the kind, then the bigger.
     */
    private static Kind[] assign(List<Box> boxes, Map<Kind, Integer> need, Map<Kind, List<Integer>> targets) {
        int n = boxes.size();
        Kind[] assigned = new Kind[n];
        Kind[] major = new Kind[n];
        for (int b = 0; b < n; b++) major[b] = majority(boxes.get(b));
        List<Kind> order = new ArrayList<>(need.keySet());
        order.removeIf(k -> need.get(k) <= 0);
        order.sort(Comparator.comparing((Kind k) -> -need.get(k)).thenComparing(Kind::ordinal));
        for (int o = 0; o < order.size(); o++) {
            Kind kind = order.get(o);
            List<Kind> later = order.subList(o + 1, order.size());
            int cap = 0;
            while (cap < need.get(kind)) {
                int pick = pick(boxes, assigned, major, kind, later, need.get(kind) - cap);
                if (pick < 0) break;
                assigned[pick] = kind;
                targets.computeIfAbsent(kind, k -> new ArrayList<>()).add(pick);
                cap += boxes.get(pick).slots();
            }
        }
        return assigned;
    }

    private static int pick(List<Box> boxes, Kind[] assigned, Kind[] major, Kind kind, List<Kind> later, int remaining) {
        List<List<Integer>> tiers = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int b = 0; b < boxes.size(); b++) {
            if (assigned[b] != null || boxes.get(b).slots() <= 0) continue;
            int tier = major[b] == kind ? 0 : major[b] == null || !later.contains(major[b]) ? 1 : 2;
            tiers.get(tier).add(b);
        }
        for (List<Integer> tier : tiers) {
            if (tier.isEmpty()) continue;
            Comparator<Integer> most = Comparator.comparing((Integer b) -> -slotsOf(boxes.get(b), kind));
            List<Integer> covering = tier.stream().filter(b -> boxes.get(b).slots() >= remaining).toList();
            if (!covering.isEmpty()) {
                return covering.stream().min(most.thenComparing(b -> boxes.get(b).stacks().size()).thenComparing(b -> b)).orElseThrow();
            }
            return tier.stream().min(most.thenComparing(b -> -boxes.get(b).slots()).thenComparing(b -> boxes.get(b).stacks().size())
                    .thenComparing(b -> b)).orElseThrow();
        }
        return -1;
    }

    private static int slotsOf(Box box, Kind kind) {
        return (int) box.stacks().stream().filter(s -> s.kind() == kind).count();
    }

    private static boolean movable(int b, String item, Map<String, Stack> info, Set<String> fixed, Kind[] assigned,
            Map<Kind, List<Integer>> targets) {
        Kind kind = info.get(item).kind();
        return !fixed.contains(item) && targets.containsKey(kind) && assigned[b] != kind;
    }

    private static int ceilDiv(int a, int b) {
        return (a + Math.max(1, b) - 1) / Math.max(1, b);
    }

    /** A box being filled and emptied slot by slot, the way ContainerActions does it. */
    private static final class Sim {
        private final int slots;
        private final List<Stack> cells;

        Sim(Box box) {
            this.slots = box.slots();
            this.cells = new ArrayList<>(box.stacks());
        }

        /** Takes up to {@code n} from the slots in order; returns how many it took. */
        int remove(String item, int n) {
            int took = 0;
            for (int i = 0; i < cells.size() && took < n; i++) {
                Stack s = cells.get(i);
                if (!s.item().equals(item)) continue;
                int k = Math.min(n - took, s.count());
                took += k;
                if (k == s.count()) cells.remove(i--);
                else cells.set(i, new Stack(item, s.count() - k, s.maxStack(), s.kind()));
            }
            return took;
        }

        /** Puts up to {@code n}: onto stacks of the item below their max, then into empty slots; returns how many. */
        int insert(String item, int n, Stack like) {
            int put = 0;
            for (int i = 0; i < cells.size() && put < n; i++) {
                Stack s = cells.get(i);
                if (!s.item().equals(item) || s.count() >= s.maxStack()) continue;
                int k = Math.min(n - put, s.maxStack() - s.count());
                cells.set(i, new Stack(item, s.count() + k, s.maxStack(), s.kind()));
                put += k;
            }
            int max = Math.max(1, like.maxStack());
            while (put < n && cells.size() < slots) {
                int k = Math.min(n - put, max);
                cells.add(new Stack(item, k, max, like.kind()));
                put += k;
            }
            return put;
        }

        Box box() {
            return new Box(slots, cells);
        }
    }
}

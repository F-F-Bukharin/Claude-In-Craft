package com.example.claudeplayer.event;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.body.AgentPlayer;
import com.example.claudeplayer.body.EatHandle;
import com.example.claudeplayer.body.SnapshotBuilder;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.safety.Hunger;
import java.util.List;

/**
 * The auto-eat reflex: every 2 s, {@link Hunger#shouldAutoEat} decides, and an eat goes into the queue at level 2
 * (a flee pauses it; it restarts after). No model call, no chat; the eat logs one INFO line. One eat at a time,
 * whether the reflex or the model queued it.
 */
public final class HungerWatcher {
    private static final int CHECK_TICKS = 40;

    private final AgentRuntime rt;
    private int count; // numbers the auto-eat-<n> call ids

    public HungerWatcher(AgentRuntime rt) {
        this.rt = rt;
    }

    public void tick() {
        if (!rt.settings().autoEat() || rt.ticks() % CHECK_TICKS != 0) return;
        AgentPlayer p = rt.body().player();
        if (p == null || !p.isAlive()) return;
        List<ActionQueue.Item> items = rt.queue().snapshot();
        if (items.stream().anyMatch(i -> i.entry().action() instanceof Action.Eat)) return;
        int running = items.stream().filter(i -> i.state() == ActionQueue.State.RUNNING)
                .mapToInt(i -> i.entry().prio()).findFirst().orElse(0);
        boolean plainFood = EatHandle.foods(p).stream().anyMatch(f -> f.kind() == Hunger.Kind.PLAIN);
        boolean hostileNear = !SnapshotBuilder.hostilesXZ(p, rt.settings().hostileRange()).isEmpty();
        if (Hunger.shouldAutoEat(p.getFoodData().getFoodLevel(), p.getHealth() < p.getMaxHealth(), plainFood, hostileNear, running)) {
            rt.queue().enqueue(List.of(new ActionQueue.Entry("auto-eat-" + ++count, "eat", new Action.Eat(true), 2)));
        }
    }
}

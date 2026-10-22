package com.example.claudeplayer.gui;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.ActionQueue;
import com.example.claudeplayer.core.brain.AgentLoop;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Claude Brain screen: a read-only chest that lists Claude's queue as it was when it opened. It is server side
 * only, so the stock container screen shows it and no client code or packet is needed.
 */
public final class BrainMenu {
    private static final int ROWS = 6;
    private static final int SLOTS = ROWS * 9;
    private static final int TITLE_TEXT_MAX = 30;

    private BrainMenu() {}

    public static void open(ServerPlayer player, AgentRuntime rt) {
        List<ActionQueue.Item> items = rt.queue().snapshot();
        boolean overflow = items.size() > SLOTS;
        int shown = overflow ? SLOTS - 1 : items.size();
        SimpleContainer cells = new SimpleContainer(SLOTS);
        int waitingNumber = 0;
        for (int i = 0; i < shown; i++) {
            ActionQueue.Item item = items.get(i);
            if (item.state() != ActionQueue.State.RUNNING) waitingNumber++;
            cells.setItem(i, cell(item, waitingNumber));
        }
        if (overflow) cells.setItem(SLOTS - 1, moreCell(items.size() - shown));
        Component title = title(rt.loop(), rt.guardrails().halted());
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ReadOnlyMenu(id, inventory, cells), title));
    }

    private static ItemStack cell(ActionQueue.Item item, int waitingNumber) {
        ActionQueue.Entry entry = item.entry();
        ItemStack stack = new ItemStack(switch (item.state()) {
            case RUNNING -> Items.LIME_DYE;
            case WAITING -> Items.PAPER;
            case PAUSED -> Items.CLOCK;
        });
        String state = switch (item.state()) {
            case RUNNING -> "Running now";
            case WAITING -> "Waiting, #" + waitingNumber;
            case PAUSED -> "Paused, #" + waitingNumber + ": resumes after a reflex";
        };
        stack.setHoverName(Component.literal(WorldText.clean(entry.tool(), WorldText.NAME_MAX)));
        stack.getOrCreateTagElement("display").put("Lore", lore("Priority " + entry.prio() + " (" + priorityName(entry.prio()) + ")", state));
        return stack;
    }

    private static ItemStack moreCell(int hidden) {
        ItemStack stack = new ItemStack(Items.BOOK);
        stack.setHoverName(Component.literal("+" + hidden + " more"));
        stack.getOrCreateTagElement("display").put("Lore", lore("Not shown in this screen"));
        return stack;
    }

    private static String priorityName(int prio) {
        return switch (prio) {
            case 1 -> "routine";
            case 2 -> "important";
            default -> "safety";
        };
    }

    private static Component title(AgentLoop loop, boolean halted) {
        String head;
        if (!loop.active()) head = halted ? "halted" : "idle";
        else if (loop.orderFrom().isEmpty()) head = "reacting to a threat";
        else head = "order from " + WorldText.clean(loop.orderFrom(), WorldText.NAME_MAX) + ": \""
                + WorldText.clean(loop.orderText(), TITLE_TEXT_MAX) + "\"";
        return Component.literal("Claude Brain: " + head);
    }

    private static ListTag lore(String... lines) {
        ListTag tag = new ListTag();
        for (String line : lines) {
            Component text = Component.literal(line).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false));
            tag.add(StringTag.valueOf(Component.Serializer.toJson(text)));
        }
        return tag;
    }

    /** A chest that takes no clicks, so nothing can be taken out of the cells or moved between them. */
    private static final class ReadOnlyMenu extends ChestMenu {
        ReadOnlyMenu(int id, Inventory inventory, Container cells) {
            super(MenuType.GENERIC_9x6, id, inventory, cells, ROWS);
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {}

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }
}

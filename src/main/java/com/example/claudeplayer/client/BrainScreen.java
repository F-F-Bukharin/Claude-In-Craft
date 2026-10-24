package com.example.claudeplayer.client;

import com.example.claudeplayer.net.BrainNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Claude Brain panel: plain text over the game, refreshed about once a second while it is open. Esc closes it.
 * Client side only.
 */
public final class BrainScreen extends Screen {
    private static final Component TITLE = Component.literal("Claude Brain");
    private static final int REFRESH_TICKS = 20;
    private static final int MAX_WIDTH = 320;
    private static final int PAD = 6;
    private static final int LINE_HEIGHT = 10;

    private List<String> lines;
    private int ticksToRefresh = REFRESH_TICKS;

    private BrainScreen(List<String> lines) {
        super(TITLE);
        this.lines = lines;
    }

    /** Called for each panel packet: opens the panel, or refreshes the one already open. */
    public static void receive(boolean open, List<String> lines) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BrainScreen panel) panel.lines = lines;
        else if (open) mc.setScreen(new BrainScreen(lines));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (--ticksToRefresh > 0) return;
        ticksToRefresh = REFRESH_TICKS;
        BrainNetwork.requestRefresh();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int panelWidth = Math.min(MAX_WIDTH, width - 4 * PAD);
        int textWidth = panelWidth - 2 * PAD;
        List<FormattedCharSequence> rows = new ArrayList<>();
        for (String line : lines) rows.addAll(font.split(Component.literal(line), textWidth));
        int panelHeight = (rows.size() + 2) * LINE_HEIGHT + 2 * PAD;
        int left = (width - panelWidth) / 2;
        int top = Math.max(PAD, (height - panelHeight) / 2);
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xC0101010);
        g.drawCenteredString(font, TITLE, width / 2, top + PAD, 0xFFFFFF);
        int y = top + PAD + 2 * LINE_HEIGHT;
        for (FormattedCharSequence row : rows) {
            g.drawString(font, row, left + PAD, y, 0xE0E0E0, false);
            y += LINE_HEIGHT;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}

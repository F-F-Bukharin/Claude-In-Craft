package com.example.claudeplayer.client;

import com.example.claudeplayer.core.brain.BrainPanel;
import com.example.claudeplayer.net.BrainNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * The Claude Brain panel: titled sections on a dark translucent panel over the game, refreshed about once a second
 * while it is open. Text wraps to the panel's width and the panel fits the window; taller content scrolls with the
 * mouse wheel and the arrow, Page and Home/End keys. Esc closes it; the game keeps running. Client side only.
 */
public final class BrainScreen extends Screen {
    private static final Component TITLE = Component.literal("Claude Brain");
    private static final int REFRESH_TICKS = 20;
    private static final int MAX_WIDTH = 360;
    private static final int MIN_TEXT_WIDTH = 40;
    private static final int MARGIN = 8; // from the window's edges
    private static final int PAD = 6; // inside the panel
    private static final int LINE_HEIGHT = 10;
    private static final int SECTION_GAP = 4;
    private static final int TITLE_GAP = 4; // under the panel's own title
    private static final int WHEEL_LINES = 3;
    private static final int PANEL_COLOR = 0xC0101010;
    private static final int BORDER_COLOR = 0xFF3A3A3A;
    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int HEADER_COLOR = 0xFFAA00;
    private static final int TEXT_COLOR = 0xE0E0E0;
    private static final int BAR_COLOR = 0x80FFFFFF;

    /** One drawn row: a wrapped piece of a line, a section header, or a gap ({@code text} null). */
    private record Row(FormattedCharSequence text, int color, int height) {}

    private List<BrainPanel.Section> sections;
    private List<Row> rows = List.of();
    private int rowsWidth = -1; // the text width the rows were wrapped for; -1 forces a relayout
    private int contentHeight;
    private double scroll; // GUI pixels scrolled down from the top of the content
    private int ticksToRefresh = REFRESH_TICKS;

    private BrainScreen(List<BrainPanel.Section> sections) {
        super(TITLE);
        this.sections = sections == null ? List.of() : sections;
    }

    /** Called for each panel packet: opens the panel, or refreshes the one already open. */
    public static void receive(boolean open, List<BrainPanel.Section> sections) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BrainScreen panel) {
            panel.sections = sections == null ? List.of() : sections;
            panel.rowsWidth = -1; // relayout on the next frame; the scroll offset stays and is clamped then
        } else if (open) {
            mc.setScreen(new BrainScreen(sections));
        }
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

    // Layout, recomputed from the window size on every frame (cheap); rows only when the width or content changes.

    private int panelWidth() {
        return Math.max(0, Math.min(MAX_WIDTH, width - 2 * MARGIN));
    }

    private int textWidth() {
        return Math.max(MIN_TEXT_WIDTH, panelWidth() - 2 * PAD - 4); // 4: room for the scrollbar
    }

    private int titleHeight() {
        return LINE_HEIGHT + TITLE_GAP;
    }

    private int panelHeight() {
        int wanted = 2 * PAD + titleHeight() + contentHeight;
        return Math.max(0, Math.min(wanted, height - 2 * MARGIN));
    }

    private int bodyHeight() {
        return Math.max(0, panelHeight() - 2 * PAD - titleHeight());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - bodyHeight());
    }

    private void layout() {
        int textWidth = textWidth();
        if (textWidth == rowsWidth) return;
        rowsWidth = textWidth;
        List<Row> out = new ArrayList<>();
        int h = 0;
        for (BrainPanel.Section section : sections) {
            if (section == null) continue;
            if (!out.isEmpty()) {
                out.add(new Row(null, 0, SECTION_GAP));
                h += SECTION_GAP;
            }
            for (FormattedCharSequence piece : font.split(Component.literal(nonNull(section.title())), textWidth)) {
                out.add(new Row(piece, HEADER_COLOR, LINE_HEIGHT));
                h += LINE_HEIGHT;
            }
            for (String line : section.lines()) {
                for (FormattedCharSequence piece : font.split(Component.literal(nonNull(line)), textWidth)) {
                    out.add(new Row(piece, TEXT_COLOR, LINE_HEIGHT));
                    h += LINE_HEIGHT;
                }
            }
        }
        rows = out;
        contentHeight = h;
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    @Override
    protected void init() {
        rowsWidth = -1; // the window was resized, or the GUI scale changed
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        layout();
        clampScroll();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        if (panelWidth <= 0 || panelHeight <= 0) return;
        int left = (width - panelWidth) / 2;
        int top = Math.max(MARGIN, (height - panelHeight) / 2);
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        g.fill(left - 1, top - 1, right + 1, bottom + 1, BORDER_COLOR);
        g.fill(left, top, right, bottom, PANEL_COLOR);
        g.enableScissor(left, top, right, bottom);
        g.drawCenteredString(font, TITLE, (left + right) / 2, top + PAD, TITLE_COLOR);
        g.disableScissor();

        int bodyTop = top + PAD + titleHeight();
        int bodyHeight = bodyHeight();
        if (bodyHeight > 0) {
            g.enableScissor(left + PAD, bodyTop, right - PAD, bodyTop + bodyHeight);
            int y = bodyTop - (int) scroll;
            for (Row row : rows) {
                if (row.text() != null && y + row.height() > bodyTop && y < bodyTop + bodyHeight) {
                    g.drawString(font, row.text(), left + PAD, y, row.color(), false);
                }
                y += row.height();
            }
            g.disableScissor();
            int max = maxScroll();
            if (max > 0) { // a thin scrollbar on the right edge
                int barX = right - PAD + 2;
                int thumb = Math.max(8, bodyHeight * bodyHeight / Math.max(1, contentHeight));
                int thumbY = bodyTop + (int) ((bodyHeight - thumb) * (scroll / max));
                g.fill(barX, thumbY, barX + 2, thumbY + thumb, BAR_COLOR);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollBy(-delta * WHEEL_LINES * LINE_HEIGHT);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int page = Math.max(LINE_HEIGHT, bodyHeight() - LINE_HEIGHT);
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> scrollBy(-LINE_HEIGHT);
            case GLFW.GLFW_KEY_DOWN -> scrollBy(LINE_HEIGHT);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollBy(-page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollBy(page);
            case GLFW.GLFW_KEY_HOME -> scrollBy(-Double.MAX_VALUE);
            case GLFW.GLFW_KEY_END -> scrollBy(Double.MAX_VALUE);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers); // Esc closes
            }
        }
        return true;
    }

    private void scrollBy(double amount) {
        scroll += amount;
        clampScroll();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}

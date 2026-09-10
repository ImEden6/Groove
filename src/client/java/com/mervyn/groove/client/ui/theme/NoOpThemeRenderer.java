package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.client.ui.CableView;
import com.mervyn.groove.client.ui.NodeGeometry;
import com.mervyn.groove.client.ui.NodeView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Flat boxes, no decoration. Proves the interaction model against the real EditorState
 * and InputController before any of the 4 real renderers exist, see
 * docs/SEQUENCER-UI-ARCHITECTURE.md section 6, step 3.
 */
public final class NoOpThemeRenderer implements ThemeRenderer {
    private static final int BACKGROUND = 0xFF202020;
    private static final int PANEL = 0xFF303030;
    private static final int NODE_HEADER = 0xFF454545;
    private static final int NODE_HEADER_SELECTED = 0xFF5A7DBF;
    private static final int NODE_BODY = 0xFF383838;
    private static final int CABLE = 0xFFAAAAAA;
    private static final int RING_OFF = 0xFF505050;
    private static final int RING_ON = 0xFFCCCC55;

    @Override
    public void drawBackground(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void drawPanel(GuiGraphics graphics, PanelKind kind, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL);
    }

    @Override
    public void drawNodeCard(GuiGraphics graphics, NodeView node, boolean selected) {
        int x = (int) node.screenOrigin().x();
        int y = (int) node.screenOrigin().y();
        int width = (int) NodeGeometry.WIDTH;
        int headerHeight = (int) NodeGeometry.HEADER_HEIGHT;
        int bodyHeight = (int) NodeGeometry.BODY_HEIGHT;
        graphics.fill(x, y, x + width, y + headerHeight, selected ? NODE_HEADER_SELECTED : NODE_HEADER);
        graphics.fill(x, y + headerHeight, x + width, y + headerHeight + bodyHeight, NODE_BODY);
    }

    @Override
    public void drawCable(GuiGraphics graphics, CableView cable, float tempoPhase) {
        int fromX = (int) cable.fromScreen().x(), fromY = (int) cable.fromScreen().y();
        int toX = (int) cable.toScreen().x();
        graphics.fill(Math.min(fromX, toX), fromY - 1, Math.max(fromX, toX), fromY + 1, CABLE);
    }

    @Override
    public void drawPort(GuiGraphics graphics, int x, int y, PortState state) {
        int color = switch (state) {
            case FREE -> 0xFF888888;
            case COMPATIBLE -> 0xFF55CC55;
            case INCOMPATIBLE -> 0xFFCC5555;
            case MAGNET -> 0xFFFFFFFF;
        };
        graphics.fill(x - 3, y - 3, x + 3, y + 3, color);
    }

    @Override
    public void drawEuclidRing(GuiGraphics graphics, int x, int y, boolean[] steps) {
        ThemeDraw.euclidRing(graphics, x, y, steps, RING_ON, RING_OFF);
    }

    @Override
    public void tickDecorative(float partialTick) {
        // Nothing to animate. The real theme renderers advance scanlines, escapements, and bloom here.
    }

    @Override
    public int textColor() { return 0xFFFFFFFF; }

    /** No texture set of its own; falls back to the always-bundled vanilla one. */
    @Override
    public String themeName() { return "vanilla"; }
}

package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.client.ui.CableView;
import com.mervyn.groove.client.ui.NodeGeometry;
import com.mervyn.groove.client.ui.NodeView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Vanilla Clean. Orthogonal stepped cables instead of curves, a pulse that jumps
 * between discrete steps rather than sliding, and a classic beveled button look for
 * node chrome. No idle decoration, Minecraft's own font renderer throughout.
 */
public final class VanillaRenderer implements ThemeRenderer {
    private static final String THEME = "vanilla";
    private static final int BACKGROUND = 0xFF373737;
    private static final int CABLE = 0xFF1A1A1A;
    private static final int INK = 0xFF1A1A1A;
    private static final int PULSE_STEPS = 8;

    @Override
    public void drawBackground(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void drawPanel(GuiGraphics graphics, PanelKind kind, int x, int y, int width, int height) {
        graphics.blitSprite(ThemeAssets.sprite(THEME, ThemeAssets.panelFile(kind)), x, y, width, height);
    }

    @Override
    public void drawNodeCard(GuiGraphics graphics, NodeView node, boolean selected) {
        int x = (int) node.screenOrigin().x(), y = (int) node.screenOrigin().y();
        int width = (int) NodeGeometry.WIDTH, headerHeight = (int) NodeGeometry.HEADER_HEIGHT, bodyHeight = (int) NodeGeometry.BODY_HEIGHT;
        String header = selected ? "node_header_selected" : "node_header";
        graphics.blitSprite(ThemeAssets.sprite(THEME, header), x, y, width, headerHeight);
        graphics.blitSprite(ThemeAssets.sprite(THEME, "node_body"), x, y + headerHeight, width, bodyHeight);
    }

    @Override
    public void drawCable(GuiGraphics graphics, CableView cable, float tempoPhase) {
        int x0 = (int) cable.fromScreen().x(), y0 = (int) cable.fromScreen().y();
        int x1 = (int) cable.toScreen().x(), y1 = (int) cable.toScreen().y();
        int midX = x0 + (x1 - x0) / 2;
        ThemeDraw.line(graphics, x0, y0, midX, y0, 2, CABLE);
        ThemeDraw.line(graphics, midX, y0, midX, y1, 2, CABLE);
        ThemeDraw.line(graphics, midX, y1, x1, y1, 2, CABLE);

        int step = Math.min(PULSE_STEPS - 1, (int) (tempoPhase * PULSE_STEPS));
        double t = (double) step / (PULSE_STEPS - 1);
        int px, py;
        if (t < 0.5) { px = x0 + (int) ((midX - x0) * (t * 2)); py = y0; }
        else { double t2 = (t - 0.5) * 2; px = midX + (int) ((x1 - midX) * t2); py = y0 + (int) ((y1 - y0) * t2); }
        graphics.fill(px - 3, py - 3, px + 3, py + 3, CABLE);
    }

    @Override
    public void drawPort(GuiGraphics graphics, int x, int y, PortState state) {
        graphics.blitSprite(ThemeAssets.sprite(THEME, ThemeAssets.portFile(state)), x - 8, y - 8, 16, 16);
    }

    @Override
    public void drawEuclidRing(GuiGraphics graphics, int x, int y, boolean[] steps) {
        ThemeDraw.euclidRingSprites(graphics, x, y, steps,
                ThemeAssets.sprite(THEME, "ring_step_on"), ThemeAssets.sprite(THEME, "ring_step_off"));
    }

    @Override
    public void tickDecorative(float partialTick) {
        // No idle decoration for this theme.
    }

    @Override
    public int textColor() { return INK; }

    @Override
    public String themeName() { return THEME; }
}

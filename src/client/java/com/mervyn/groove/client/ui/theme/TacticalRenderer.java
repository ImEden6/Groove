package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.client.ui.CableView;
import com.mervyn.groove.client.ui.NodeGeometry;
import com.mervyn.groove.client.ui.NodeView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Tactical Studio Rack. Smooth bezier cables with a glow pass, a particle riding the
 * cable on the beat, drop shadowed node cards, no idle decoration. Palette matches
 * scratch/tools/themes/__init__.py so this and generate_atlas.py agree.
 */
public final class TacticalRenderer implements ThemeRenderer {
    private static final String THEME = "tactical";
    private static final int BACKGROUND = 0xFF1A1C20;
    private static final int SHADOW = 0x80000000;
    private static final int CABLE_GLOW = 0x5000E5FF;
    private static final int CABLE_CORE = 0xFF00E5FF;
    private static final int PULSE = 0xFFFFAA00;
    private static final int INK = 0xFFE7E9EE;

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
        graphics.fill(x + 2, y + 3, x + width + 2, y + headerHeight + bodyHeight + 3, SHADOW);
        String header = selected ? "node_header_selected" : "node_header";
        graphics.blitSprite(ThemeAssets.sprite(THEME, header), x, y, width, headerHeight);
        graphics.blitSprite(ThemeAssets.sprite(THEME, "node_body"), x, y + headerHeight, width, bodyHeight);
    }

    @Override
    public void drawCable(GuiGraphics graphics, CableView cable) {
        double lastX = cable.fromScreen().x(), lastY = cable.fromScreen().y();
        int steps = 24;
        for (int i = 1; i <= steps; i++) {
            double[] p = point(cable, (double) i / steps);
            ThemeDraw.line(graphics, lastX, lastY, p[0], p[1], 5, CABLE_GLOW);
            ThemeDraw.line(graphics, lastX, lastY, p[0], p[1], 1.5, CABLE_CORE);
            lastX = p[0]; lastY = p[1];
        }
    }

    @Override
    public void drawCablePulse(GuiGraphics graphics, CableView cable, float tempoPhase) {
        double[] p = point(cable, tempoPhase);
        graphics.fill((int) p[0] - 2, (int) p[1] - 2, (int) p[0] + 2, (int) p[1] + 2, PULSE);
    }

    private static double[] point(CableView cable, double t) {
        double x0 = cable.fromScreen().x(), y0 = cable.fromScreen().y();
        double x3 = cable.toScreen().x(), y3 = cable.toScreen().y();
        double sag = Math.min(140, Math.max(24, Math.abs(x3 - x0) * 0.4));
        return new double[] {ThemeDraw.cubic(x0, x0 + sag, x3 - sag, x3, t), ThemeDraw.cubic(y0, y0, y3, y3, t)};
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

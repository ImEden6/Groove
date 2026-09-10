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
    public void drawCable(GuiGraphics graphics, CableView cable, float tempoPhase) {
        double x0 = cable.fromScreen().x(), y0 = cable.fromScreen().y();
        double x3 = cable.toScreen().x(), y3 = cable.toScreen().y();
        double sag = Math.min(140, Math.max(24, Math.abs(x3 - x0) * 0.4));
        double x1 = x0 + sag, y1 = y0, x2 = x3 - sag, y2 = y3;
        double lastX = x0, lastY = y0;
        int steps = 24;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = ThemeDraw.cubic(x0, x1, x2, x3, t), y = ThemeDraw.cubic(y0, y1, y2, y3, t);
            ThemeDraw.line(graphics, lastX, lastY, x, y, 5, CABLE_GLOW);
            ThemeDraw.line(graphics, lastX, lastY, x, y, 1.5, CABLE_CORE);
            lastX = x; lastY = y;
        }
        double px = ThemeDraw.cubic(x0, x1, x2, x3, tempoPhase), py = ThemeDraw.cubic(y0, y1, y2, y3, tempoPhase);
        graphics.fill((int) px - 2, (int) py - 2, (int) px + 2, (int) py + 2, PULSE);
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

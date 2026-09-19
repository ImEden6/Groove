package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.client.ui.CableView;
import com.mervyn.groove.client.ui.NodeGeometry;
import com.mervyn.groove.client.ui.NodeView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Retro CRT. Straight unshaded cables with a bright segment riding the beat, wireframe
 * node chrome that flares on selection, and a scrolling scanline overlay for idle
 * decoration. No bezier curves anywhere, this theme is all straight lines.
 */
public final class CrtRenderer implements ThemeRenderer {
    private static final String THEME = "crt";
    private static final int BACKGROUND = 0xFF080C0A;
    private static final int BORDER_SOFT = 0xFF163524;
    private static final int PHOSPHOR = 0xFF33FF66;
    private static final int SCANLINE = 0x2000FF66;

    private int scanOffset;

    @Override
    public void drawBackground(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        for (int y = -4 + scanOffset % 4; y < height; y += 4) graphics.fill(0, y, width, y + 1, SCANLINE);
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
        graphics.fill(x, y + headerHeight, x + width, y + headerHeight + 1, BORDER_SOFT);
    }

    @Override
    public void drawCable(GuiGraphics graphics, CableView cable) {
        ThemeDraw.line(graphics, cable.fromScreen().x(), cable.fromScreen().y(), cable.toScreen().x(), cable.toScreen().y(), 1, BORDER_SOFT);
    }

    @Override
    public void drawCablePulse(GuiGraphics graphics, CableView cable, float tempoPhase) {
        double x0 = cable.fromScreen().x(), y0 = cable.fromScreen().y();
        double x1 = cable.toScreen().x(), y1 = cable.toScreen().y();
        double px = x0 + (x1 - x0) * tempoPhase, py = y0 + (y1 - y0) * tempoPhase;
        double segment = Math.max(6, Math.hypot(x1 - x0, y1 - y0) * 0.08);
        double dx = (x1 - x0), dy = (y1 - y0);
        double length = Math.max(1, Math.hypot(dx, dy));
        double ux = dx / length, uy = dy / length;
        ThemeDraw.line(graphics, px - ux * segment / 2, py - uy * segment / 2, px + ux * segment / 2, py + uy * segment / 2, 1, PHOSPHOR);
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
        scanOffset = (int) (System.nanoTime() / 20_000_000L); // 50 steps per second, framerate-independent
    }

    @Override
    public int textColor() { return PHOSPHOR; }

    @Override
    public String themeName() { return THEME; }
}

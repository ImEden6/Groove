package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.client.ui.CableView;
import com.mervyn.groove.client.ui.NodeGeometry;
import com.mervyn.groove.client.ui.NodeView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Acoustic Clockwork. Heavier sag on the cables and no live pulse riding them, brass
 * node chrome with corner rivets, and a small escapement gear that ticks in the corner
 * on a fixed interval instead of animating per beat.
 */
public final class ClockworkRenderer implements ThemeRenderer {
    private static final String THEME = "clockwork";
    private static final int BACKGROUND = 0xFFE8DFD0;
    private static final int BORDER = 0xFF8C6A1F;
    private static final int RIVET = 0xFF6D7A3D;
    private static final int CABLE = 0xFF8C6A1F;
    private static final int INK = 0xFF2C2620;

    private static final long TICK_INTERVAL_MILLIS = 700;
    private long lastTickAt = -1;
    private boolean gearAdvanced;

    @Override
    public void drawBackground(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        drawGear(graphics, width - 18, height - 18, gearAdvanced);
    }

    private void drawGear(GuiGraphics graphics, int x, int y, boolean advanced) {
        int radius = 8, spokes = 6;
        for (int i = 0; i < spokes; i++) {
            double angle = 2 * Math.PI * i / spokes + (advanced ? Math.PI / spokes : 0);
            int tipX = x + (int) Math.round(Math.cos(angle) * radius);
            int tipY = y + (int) Math.round(Math.sin(angle) * radius);
            ThemeDraw.line(graphics, x, y, tipX, tipY, 2, BORDER);
        }
        graphics.fill(x - 3, y - 3, x + 3, y + 3, RIVET);
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
        int rivetInset = 4;
        graphics.fill(x + rivetInset - 1, y + rivetInset - 1, x + rivetInset + 1, y + rivetInset + 1, RIVET);
        graphics.fill(x + width - rivetInset - 1, y + rivetInset - 1, x + width - rivetInset + 1, y + rivetInset + 1, RIVET);
    }

    @Override
    public void drawCable(GuiGraphics graphics, CableView cable) {
        double x0 = cable.fromScreen().x(), y0 = cable.fromScreen().y();
        double x3 = cable.toScreen().x(), y3 = cable.toScreen().y();
        double sag = Math.min(160, Math.max(40, Math.abs(x3 - x0) * 0.7));
        double x1 = x0 + sag, y1 = y0 + sag * 0.3, x2 = x3 - sag, y2 = y3 + sag * 0.3;
        double lastX = x0, lastY = y0;
        int steps = 24;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = ThemeDraw.cubic(x0, x1, x2, x3, t), y = ThemeDraw.cubic(y0, y1, y2, y3, t);
            ThemeDraw.line(graphics, lastX, lastY, x, y, 2, CABLE);
            lastX = x; lastY = y;
        }
        // No live pulse in this theme. The beat shows on the escapement gear, not the cable.
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
        long now = System.currentTimeMillis();
        if (lastTickAt < 0) { lastTickAt = now; return; }
        if (now - lastTickAt >= TICK_INTERVAL_MILLIS) {
            lastTickAt = now;
            gearAdvanced = !gearAdvanced;
        }
    }

    @Override
    public int textColor() { return INK; }

    @Override
    public String themeName() { return THEME; }
}

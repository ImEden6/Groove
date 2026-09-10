package com.mervyn.groove.client.ui.theme;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Drawing primitives GuiGraphics does not provide on its own: an arbitrary angle line
 * and a cubic bezier point. Pure geometry, shared by the theme renderers. No per-theme
 * behavior lives here, only the math that each renderer's cable geometry is built from.
 */
final class ThemeDraw {
    private ThemeDraw() {}

    /** A line of the given thickness between two points, built out of small filled squares
     *  since GuiGraphics has no line primitive at an arbitrary angle. */
    static void line(GuiGraphics graphics, double x0, double y0, double x1, double y1, double thickness, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 2));
        double half = Math.max(0.5, thickness / 2);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int cx = (int) Math.round(x0 + dx * t);
            int cy = (int) Math.round(y0 + dy * t);
            graphics.fill((int) (cx - half), (int) (cy - half), (int) Math.ceil(cx + half), (int) Math.ceil(cy + half), color);
        }
    }

    static double cubic(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }

    static void euclidRing(GuiGraphics graphics, int x, int y, boolean[] steps, int onColor, int offColor) {
        int radius = 20;
        for (int i = 0; i < steps.length; i++) {
            double angle = 2 * Math.PI * i / steps.length;
            int stepX = x + (int) Math.round(Math.cos(angle) * radius);
            int stepY = y + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(stepX - 2, stepY - 2, stepX + 2, stepY + 2, steps[i] ? onColor : offColor);
        }
    }

    /** Same ring layout, drawing the theme's ring_step_on/off sprites (16x16, centered
     *  on each step position) instead of flat dots. */
    static void euclidRingSprites(GuiGraphics graphics, int x, int y, boolean[] steps,
            net.minecraft.resources.ResourceLocation onSprite, net.minecraft.resources.ResourceLocation offSprite) {
        int radius = 20;
        for (int i = 0; i < steps.length; i++) {
            double angle = 2 * Math.PI * i / steps.length;
            int stepX = x + (int) Math.round(Math.cos(angle) * radius);
            int stepY = y + (int) Math.round(Math.sin(angle) * radius);
            graphics.blitSprite(steps[i] ? onSprite : offSprite, stepX - 8, stepY - 8, 16, 16);
        }
    }
}

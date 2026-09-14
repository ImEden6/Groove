package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.ThemeAssets;
import com.mojang.math.Axis;
import groove.engine.Graph;
import groove.engine.NodeParam;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A sprite-backed control; gestures remain owned by InputController/EditorState. */
public record RotaryKnob(String param, int x, int y, int width) {
    public static final int HEIGHT = 48;
    private static final int SIZE = 24;

    public boolean contains(double px, double py) {
        return px >= x && px < x + width && py >= y && py < y + HEIGHT;
    }

    public void draw(GuiGraphics graphics, Font font, String theme, int color,
                     Graph.Node node, double value, int mouseX, int mouseY, boolean focused) {
        if (contains(mouseX, mouseY)) graphics.fill(x, y, x + width, y + HEIGHT, 0x20ffffff);
        if (focused) {
            // A thin outline so keyboard focus is visible even when the mouse isn't hovering.
            graphics.fill(x, y, x + width, y + 1, 0xffffffff);
            graphics.fill(x, y + HEIGHT - 1, x + width, y + HEIGHT, 0xffffffff);
            graphics.fill(x, y, x + 1, y + HEIGHT, 0xffffffff);
            graphics.fill(x + width - 1, y, x + width, y + HEIGHT, 0xffffffff);
        }
        String label = font.plainSubstrByWidth(param, Math.max(0, width - 4));
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y, color, false);
        int left = x + (width - SIZE) / 2;
        graphics.blitSprite(ThemeAssets.sprite(theme, "knob_base"), left, y + 10, SIZE, SIZE);
        // The generated indicator points upwards. Rotate only it, not the base or text.
        graphics.pose().pushPose();
        graphics.pose().translate(left + SIZE / 2f, y + 10 + SIZE / 2f, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(KnobScale.angle(node, param, value)));
        graphics.blitSprite(ThemeAssets.sprite(theme, "knob_indicator"), -SIZE / 2, -SIZE / 2, SIZE, SIZE);
        graphics.pose().popPose();
        String text = param.equals(NodeParam.WAVE) ? (value < .5 ? "Sine" : "Saw")
                : value == Math.rint(value) ? Long.toString((long) value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
        text = font.plainSubstrByWidth(text, Math.max(0, width - 4));
        graphics.drawString(font, text, x + (width - font.width(text)) / 2, y + 36, color, false);
    }
}

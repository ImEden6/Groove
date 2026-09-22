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
    private static final String[] FILTER_NAMES = {"LPF", "HPF", "BPF", "Notch"};
    private static final String[] SCALE_NAMES = {"Major", "Minor", "Dorian", "Phrygian", "Lydian", "Mixolydian", "Min Pent", "Blues", "Whole Tone"};
    private static final String[] CHORD_NAMES = {"Major", "Minor", "7", "Maj7", "Min7", "Sus4", "Dim", "9"};
    private static final String[] LFO_WAVE_NAMES = {"Sine", "Triangle", "Square", "Saw"};
    private static final String[] DELAY_DIVISION_NAMES = {"1/16", "1/8T", "1/8", "1/4T", "1/8D", "1/4", "1/4D", "1/2"};

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
        String text = param.equals(NodeParam.REVERSE) ? (value < .5 ? "Forward" : "Reverse")
                : param.equals(NodeParam.END_FRAME) && value == 0 ? "Asset end"
                : node.type() == groove.engine.NodeType.FILTER && param.equals(NodeParam.MODE)
                ? FILTER_NAMES[Math.max(0, Math.min(3, (int)value))]
                : node.type() == groove.engine.NodeType.SCALE_SEQUENCE && param.equals(NodeParam.SCALE)
                ? SCALE_NAMES[Math.max(0, Math.min(8, (int)value))]
                : node.type() == groove.engine.NodeType.CHORD && param.equals(NodeParam.CHORD)
                ? CHORD_NAMES[Math.max(0, Math.min(7, (int)value))]
                : node.type() == groove.engine.NodeType.TONE && param.equals(NodeParam.WAVE)
                ? (value < .5 ? "Sine" : value < 1.5 ? "Saw" : "Pulse")
                : node.type() == groove.engine.NodeType.LFO && param.equals(NodeParam.WAVE)
                ? LFO_WAVE_NAMES[Math.max(0, Math.min(3, (int)value))]
                : node.type() == groove.engine.NodeType.WORLD && param.equals(NodeParam.SOURCE)
                ? groove.engine.WorldInputs.NAMES[Math.max(0, Math.min(groove.engine.WorldInputs.COUNT - 1, (int)value))]
                : node.type() == groove.engine.NodeType.DELAY && param.equals(NodeParam.SYNC)
                ? (value < .5 ? "Free" : "Sync")
                : node.type() == groove.engine.NodeType.DELAY && param.equals(NodeParam.FREE_RUN)
                ? (value < .5 ? "Limited" : "Free-run")
                : node.type() == groove.engine.NodeType.DELAY && param.equals(NodeParam.DIVISION)
                ? DELAY_DIVISION_NAMES[Math.max(0, Math.min(7, (int)value))]
                : value == Math.rint(value) ? Long.toString((long) value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
        text = font.plainSubstrByWidth(text, Math.max(0, width - 4));
        graphics.drawString(font, text, x + (width - font.width(text)) / 2, y + 36, color, false);
    }
}

package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.PanelKind;
import com.mervyn.groove.client.ui.theme.ThemeRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Encapsulates the nearby speaker tower binding list in the editor's right inspector area:
 * scrolling, distance/coordinate labels, and linked state badges.
 */
public final class SpeakerListPanel {
    private int scroll;

    public int scroll() { return scroll; }

    public void clampScroll(int totalSpeakers, int rows) {
        scroll = Math.min(scroll, Math.max(0, totalSpeakers - rows));
    }

    public void mouseScrolled(double scrollY, int totalSpeakers, int rows) {
        scroll = Math.max(0, Math.min(Math.max(0, totalSpeakers - rows), scroll - (int) Math.signum(scrollY)));
    }

    public int clickedRow(double mouseY, int bottom, int totalSpeakers) {
        if (mouseY >= 60 && mouseY < 60 + AccessPanel.accessRows(bottom) * 11) {
            int row = scroll + (int) ((mouseY - 60) / 11);
            if (row >= 0 && row < totalSpeakers) return row;
        }
        return -1;
    }

    public void render(GuiGraphics graphics, Font font, ThemeRenderer renderer, int textColor,
                       int x, int width, int bottom, int closeX,
                       boolean searching, List<BlockPos> speakerPositions, List<Boolean> speakerLinked) {
        renderer.drawPanel(graphics, PanelKind.DRAWER, x - 4, 26, width - (x - 4), bottom - 26);
        int textX = x + 4;
        graphics.drawString(font, "Speakers", textX, 34, textColor, false);
        graphics.drawString(font, "x", closeX, 34, textColor, false);
        graphics.drawString(font, font.plainSubstrByWidth("Click to link/unlink", width - textX - 8), textX, 48, textColor, false);
        int y = 60;
        if (searching && speakerPositions.isEmpty()) graphics.drawString(font, "Searching...", textX, y, textColor, false);
        else if (speakerPositions.isEmpty()) graphics.drawString(font, "No speakers within reach", textX, y, textColor, false);
        int rows = AccessPanel.accessRows(bottom);
        for (int i = scroll; i < Math.min(speakerPositions.size(), scroll + rows); i++) {
            var pos = speakerPositions.get(i);
            boolean isLinked = i < speakerLinked.size() && speakerLinked.get(i);
            String label = pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + (isLinked ? "  [Linked]" : "");
            int color = isLinked ? 0x55ff55 : textColor;
            graphics.drawString(font, font.plainSubstrByWidth(label, width - textX - 8), textX, y, color, false);
            y += 11;
        }
        graphics.drawString(font, font.plainSubstrByWidth("Click a row; scroll for more", width - textX - 8), textX, bottom - 54, 0xffcc66, false);
    }
}

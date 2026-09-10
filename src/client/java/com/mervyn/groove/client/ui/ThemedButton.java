package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.ThemeAssets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * A push button skinned with the active theme's button_default/button_hover/button_disabled
 * sprites instead of Minecraft's own widget sprites, so Apply/Play-Stop/Reload actually look
 * like part of the sequencer chrome rather than a stock vanilla GUI button. Falls back to the
 * theme's hazard sprite the same way every other themed draw call does (see ThemeAssets) if
 * the active theme's resourcepack isn't installed.
 */
public final class ThemedButton extends AbstractButton {
    /** Named distinctly from AbstractButton.OnPress since that one is package-private. */
    public interface Action { void run(ThemedButton button); }

    private static final int ICON_SIZE = 16;

    private final Font font;
    private final String theme;
    private final int textColor;
    private final Action action;
    private final Supplier<String> iconSprite; // "icon_play"/"icon_stop", or null for a plain text button

    public ThemedButton(int x, int y, int width, int height, Component message, Font font, String theme, int textColor, Action action) {
        this(x, y, width, height, message, font, theme, textColor, action, null);
    }

    public ThemedButton(int x, int y, int width, int height, Component message, Font font, String theme, int textColor,
            Action action, Supplier<String> iconSprite) {
        super(x, y, width, height, message);
        this.font = font;
        this.theme = theme;
        this.textColor = textColor;
        this.action = action;
        this.iconSprite = iconSprite;
    }

    @Override
    public void onPress() { action.run(this); }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        String sprite = !active ? "button_disabled" : isHoveredOrFocused() ? "button_hover" : "button_default";
        graphics.blitSprite(ThemeAssets.sprite(theme, sprite), getX(), getY(), getWidth(), getHeight());
        int color = active ? textColor : (textColor & 0xFFFFFF) | 0x80000000;
        int regionX = getX(), regionWidth = getWidth();
        if (iconSprite != null) {
            int iconX = getX() + 4, iconY = getY() + (getHeight() - ICON_SIZE) / 2;
            graphics.blitSprite(ThemeAssets.sprite(theme, iconSprite.get()), iconX, iconY, ICON_SIZE, ICON_SIZE);
            regionX = iconX + ICON_SIZE + 2;
            regionWidth = getX() + getWidth() - regionX;
        }
        // drawCenteredString always draws with a shadow and there's no overload to turn it off,
        // which clashes with the rest of this screen's shadow-free text -- so center by hand
        // and go through drawString(..., false) like everything else does. Clamped so a label
        // too wide for its region (e.g. "Play/Stop" next to the icon) keeps a right margin
        // instead of running past the button's own edge.
        int textX = regionX + (regionWidth - font.width(getMessage())) / 2;
        textX = Math.min(textX, getX() + getWidth() - 4 - font.width(getMessage()));
        graphics.drawString(font, getMessage(), textX, getY() + (getHeight() - 8) / 2, color, false);
    }
}

package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.PanelKind;
import com.mervyn.groove.client.ui.theme.ThemeRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Encapsulates the allowlist management panel in the editor's right inspector area:
 * player name entry box, Add/Remove buttons, scrollable editor list, and hint rendering.
 */
public final class AccessPanel {
    private int scroll;
    private EditBox nameBox;
    private ThemedButton addButton, removeButton;

    public void init(Font font, String theme, int textColor, int x, int width, int bottom,
                     Consumer<EditBox> addEditBox,
                     Consumer<ThemedButton> addButtonWidget,
                     Consumer<Boolean> onAction) {
        String name = nameBox == null ? "" : nameBox.getValue();
        nameBox = new EditBox(font, x, bottom - 40, width - x - 8, 18, Component.literal("Player name"));
        nameBox.setMaxLength(36);
        nameBox.setValue(name);
        addEditBox.accept(nameBox);

        int buttonWidth = (width - x - 12) / 2;
        addButton = new ThemedButton(x, bottom - 20, buttonWidth, 18, Component.literal("Add"), font, theme, textColor,
                b -> onAction.accept(true));
        addButtonWidget.accept(addButton);

        removeButton = new ThemedButton(x + buttonWidth + 4, bottom - 20, buttonWidth, 18, Component.literal("Remove"), font, theme, textColor,
                b -> onAction.accept(false));
        addButtonWidget.accept(removeButton);
    }

    public void updateVisibility(boolean visible, boolean isOwner) {
        boolean showWidgets = visible && isOwner;
        if (nameBox != null) nameBox.visible = showWidgets;
        if (addButton != null) addButton.visible = showWidgets;
        if (removeButton != null) removeButton.visible = showWidgets;
        if (nameBox != null && !showWidgets && nameBox.isFocused()) {
            nameBox.setFocused(false);
        }
    }

    public boolean isNameBoxFocused() {
        return nameBox != null && nameBox.visible && nameBox.isFocused();
    }

    public void clearFocus() {
        if (nameBox != null) nameBox.setFocused(false);
    }

    public String nameValue() {
        return nameBox == null ? "" : nameBox.getValue().trim();
    }

    public void setNameValue(String name) {
        if (nameBox != null) nameBox.setValue(name);
    }

    public int scroll() { return scroll; }

    public void clampScroll(int totalEditors, int rows) {
        scroll = Math.min(scroll, Math.max(0, totalEditors - rows));
    }

    public void mouseScrolled(double scrollY, int totalEditors, int rows) {
        scroll = Math.max(0, Math.min(Math.max(0, totalEditors - rows), scroll - (int) Math.signum(scrollY)));
    }

    public boolean mouseClicked(double mouseY, int bottom, boolean isOwner, List<String> editorNames) {
        if (isOwner && mouseY >= 60 && mouseY < 60 + accessRows(bottom) * 11) {
            int row = scroll + (int) ((mouseY - 60) / 11);
            if (row >= 0 && row < editorNames.size()) {
                setNameValue(editorNames.get(row));
                return true;
            }
        }
        return false;
    }

    public static int accessRows(int bottom) {
        return Math.max(0, (bottom - 120) / 11);
    }

    public void render(GuiGraphics graphics, Font font, ThemeRenderer renderer, int textColor,
                       int x, int width, int bottom, int closeX, String ownerName, List<String> editorNames, boolean isOwner) {
        renderer.drawPanel(graphics, PanelKind.DRAWER, x - 4, 26, width - (x - 4), bottom - 26);
        int textX = x + 4;
        graphics.drawString(font, "Access", textX, 34, textColor, false);
        graphics.drawString(font, "x", closeX, 34, textColor, false);
        graphics.drawString(font, font.plainSubstrByWidth("Owner: " + (ownerName.isEmpty() ? "(unclaimed)" : ownerName), width - textX - 8), textX, 48, textColor, false);
        int y = 60;
        if (editorNames.isEmpty()) graphics.drawString(font, "No editors added", textX, y, textColor, false);
        for (int i = scroll; i < Math.min(editorNames.size(), scroll + accessRows(bottom)); i++) {
            graphics.drawString(font, font.plainSubstrByWidth(editorNames.get(i), width - textX - 8), textX, y, textColor, false);
            y += 11;
        }
        String hint = isOwner ? "Click a name; scroll for more" : "Only owner can change access";
        graphics.drawString(font, font.plainSubstrByWidth(hint, width - textX - 8), textX, bottom - 54, 0xffcc66, false);
    }
}

package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.client.ui.CableView;
import com.mervyn.groove.client.ui.NodeView;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Everything that draws the sequencer editor, one implementation per visual theme. This
 * interface only reads state that EditorState and InputController already own, it never
 * mutates it, since interaction is one shared codepath and rendering is the only place
 * the 4 themes actually differ. See docs/SEQUENCER-UI-ARCHITECTURE.md section 3.
 */
public interface ThemeRenderer {
    void drawBackground(GuiGraphics graphics, int width, int height);
    void drawPanel(GuiGraphics graphics, PanelKind kind, int x, int y, int width, int height);
    void drawNodeCard(GuiGraphics graphics, NodeView node, boolean selected);
    void drawCable(GuiGraphics graphics, CableView cable, float tempoPhase);
    void drawPort(GuiGraphics graphics, int x, int y, PortState state);
    void drawEuclidRing(GuiGraphics graphics, int x, int y, boolean[] steps);
    /** Advances scanline scroll, escapement ticks, bloom timing, or whatever else a theme
     *  animates on its own clock. A no-op for themes with no idle decoration. */
    void tickDecorative(float partialTick);
    /** Readable text color against this theme's panel fills, for the drawer, inspector,
     *  and message bar text GrooveEditorScreen draws directly rather than through a
     *  dedicated draw call. */
    int textColor();
    /** The theme namespace ThemeAssets.sprite resolves against, e.g. for ThemedButton
     *  (Apply/Play-Stop/Reload) so they use this theme's button_default/hover/disabled
     *  instead of Minecraft's own widget sprites. */
    String themeName();
}

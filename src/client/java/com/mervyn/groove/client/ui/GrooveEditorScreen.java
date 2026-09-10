package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.NoOpThemeRenderer;
import com.mervyn.groove.client.ui.theme.PanelKind;
import com.mervyn.groove.client.ui.theme.PortState;
import com.mervyn.groove.client.ui.theme.ThemeRenderer;
import groove.engine.Graph;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import com.mervyn.groove.client.music.MusicClient;
import com.mervyn.groove.client.music.SampleLibrary;
import com.mervyn.groove.client.music.SampleCommands;
import com.mervyn.groove.music.MusicPackets;
import com.mervyn.groove.music.GraphJson;
import groove.engine.samples.SampleCatalog;
import groove.engine.samples.AssetRef;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.EditBox;

/**
 * The node editor screen. Owns one EditorState and one InputController and drives both
 * against a ThemeRenderer, with explicit revision-aware submission and sample browsing.
 * See docs/EDITOR-INTEGRATION.md.
 */
public final class GrooveEditorScreen extends Screen {
    private final EditorState state;
    private final InputController input;
    private final ThemeRenderer renderer;
    private UUID baseEpoch, request;
    private long baseRevision, submittedAt;
    private Graph baseGraph, submittedGraph;
    private String message = "Edit locally, then Apply. Drag samples onto the canvas or a generator.";
    private EditBox search, bpm;
    private boolean drawer = true, browsing, sampleDragging;
    private int scroll, selectedSample;
    private AssetRef draggedSample;
    private double sampleDownX, sampleDownY;
    private static final int DRAWER_WIDTH = 160;
    private static final List<String> SPAWNABLE_TYPES = List.of("tone", "euclid", "fast", "stack", "output");
    private int quickSpawnSelected;
    private AssetRef waveRef;
    private float[] waveform;
    private boolean confirmClose;

    public GrooveEditorScreen(Graph graph) { this(graph, new NoOpThemeRenderer()); }

    public GrooveEditorScreen(Graph graph, ThemeRenderer renderer) {
        super(Component.literal("Groove"));
        state = new EditorState(graph);
        input = new InputController(state);
        this.renderer = renderer;
        baseGraph = graph; baseEpoch = MusicClient.epoch();
        baseRevision = MusicClient.snapshot() == null ? -1 : MusicClient.snapshot().revision();
        state.onTogglePlay(() -> submit(true));
        state.onGraphChanged(() -> confirmClose = false);
    }

    @Override
    protected void init() {
        input.setViewport(width, height);
        String theme = renderer.themeName();
        int textColor = renderer.textColor();
        addRenderableWidget(new ThemedButton(4, 2, 46, 20, Component.literal("Apply"), font, theme, textColor, b -> submit(false)));
        addRenderableWidget(new ThemedButton(54, 2, 79, 20, Component.literal("Play/Stop"), font, theme, textColor, b -> submit(true), this::playStateIcon));
        addRenderableWidget(new ThemedButton(137, 2, 52, 20, Component.literal("Reload"), font, theme, textColor, b -> reloadSession()));
        String tempo = bpm == null ? Double.toString(MusicClient.desiredState() == null ? 128 : MusicClient.desiredState().bpm()) : bpm.getValue();
        bpm = new EditBox(font, 193, 2, 48, 20, Component.literal("BPM")); bpm.setMaxLength(7); bpm.setValue(tempo); addRenderableWidget(bpm);
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, 5, 40, DRAWER_WIDTH - 10, 18, Component.literal("Search samples"));
        search.setMaxLength(160); search.setValue(query); search.setResponder(s -> { scroll = 0; selectedSample = 0; });
        search.visible = drawer; addRenderableWidget(search);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        state.tick();
        renderer.tickDecorative(partialTick);
        // Use the same server clock domain as playback, including scheduled transitions.
        var snapshot = MusicClient.snapshot();
        var live = snapshot == null ? null : snapshot.at(MusicClient.serverNow());
        double cycle = live == null ? 0 : live.cycleAt(MusicClient.serverNow());
        float tempoPhase = (float) (cycle - Math.floor(cycle));
        renderer.drawBackground(graphics, width, height);
        renderer.drawPanel(graphics, PanelKind.TRANSPORT, 0, 0, width, 24);

        for (Graph.Edge edge : state.edges()) {
            Vec2 from = state.layout().get(edge.fromNode());
            Vec2 to = state.layout().get(edge.toNode());
            if (from == null || to == null) continue;
            Vec2 fromScreen = state.toScreen(NodeGeometry.outputPort(from));
            Vec2 toScreen = state.toScreen(NodeGeometry.inputPort(to));
            renderer.drawCable(graphics, new CableView(fromScreen, toScreen, tempoPhase), tempoPhase);
        }
        if (state.isWireDragging()) {
            EditorState.WireDrag drag = state.wireDrag();
            Vec2 from = state.layout().get(drag.fromNode());
            if (from != null) {
                Vec2 fromScreen = state.toScreen(NodeGeometry.outputPort(from));
                Vec2 toScreen = state.toScreen(drag.pointer());
                renderer.drawCable(graphics, new CableView(fromScreen, toScreen, tempoPhase), tempoPhase);
            }
        }
        for (Graph.Node node : state.nodes()) {
            Vec2 origin = state.layout().get(node.id());
            if (origin == null) continue;
            Vec2 screenOrigin = state.toScreen(origin);
            graphics.pose().pushPose();
            graphics.pose().translate(screenOrigin.x(), screenOrigin.y(), 0);
            graphics.pose().scale((float) state.zoom(), (float) state.zoom(), 1);
            renderer.drawNodeCard(graphics, new NodeView(node, new Vec2(0, 0)), state.selection().contains(node.id()));
            // 8/8/30, not 4/6/28: the node_header/node_body sprites carry a real 6px drawn
            // border now (see docs/SEQUENCER-UI-ARCHITECTURE.md section 4a), and text flush
            // against the old flat-fill inset used to sit right on top of it.
            graphics.drawString(font, font.plainSubstrByWidth(node.id(), 136), 8, 8, renderer.textColor(), false);
            graphics.drawString(font, font.plainSubstrByWidth(node.type(), 136), 8, 30, renderer.textColor(), false);
            graphics.pose().popPose();
            if (state.hasOutputPort(node.id())) {
                Vec2 port = state.toScreen(NodeGeometry.outputPort(origin));
                renderer.drawPort(graphics, (int) port.x(), (int) port.y(), PortState.FREE);
            }
            if (state.hasInputPort(node.id())) {
                Vec2 port = state.toScreen(NodeGeometry.inputPort(origin));
                renderer.drawPort(graphics, (int) port.x(), (int) port.y(), PortState.FREE);
            }
        }
        if (request != null && System.nanoTime() - submittedAt > 10_000_000_000L) {
            request = null; message = "No acknowledgement. Reload before retrying; your draft is still here.";
        }
        int textColor = renderer.textColor();
        if (drawer) {
            renderer.drawPanel(graphics, PanelKind.DRAWER, 0, 24, DRAWER_WIDTH, height - 44);
            graphics.drawString(font, "Sample Vault  (Ctrl+B)", 8, 32, textColor, false);
            var rows = samples(); clampScroll(rows.size());
            for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
                int y = 64 + (i - scroll) * 16;
                if (i == selectedSample) graphics.fill(2, y - 2, DRAWER_WIDTH - 2, y + 13, 0x5000e5ff);
                graphics.drawString(font, font.plainSubstrByWidth(rows.get(i).ref().assetId(), DRAWER_WIDTH - 13), 8, y, textColor, false);
            }
        }
        if (!state.selection().isEmpty()) {
            var node = state.node(state.selection().iterator().next());
            int x = Math.max(DRAWER_WIDTH + 10, width - 150);
            int inspectorBottom = Math.min(height - 20, 220);
            renderer.drawPanel(graphics, PanelKind.DRAWER, x - 4, 26, width - (x - 4), inspectorBottom - 26);
            // Text sits 4px further right / 8px lower than the panel's own origin, past its
            // 6px drawn border -- same reasoning as the node card and drawer text below.
            int textX = x + 4;
            graphics.drawString(font, "Inspector: " + node.id(), textX, 34, textColor, false);
            if (node.sample() != null) {
                graphics.drawString(font, font.plainSubstrByWidth(node.sample().assetId(), width - textX), textX, 46, textColor, false);
                String status = MusicClient.sampleStatus().getOrDefault(node.sample(), SampleLibrary.catalog().status(node.sample()).name());
                graphics.drawString(font, font.plainSubstrByWidth(status, width - textX), textX, 62, 0xffcc66, false);
                graphics.drawString(font, "Drop a sample to replace", textX, 78, textColor, false);
                if (!node.sample().equals(waveRef)) {
                    waveRef = node.sample(); waveform = null;
                    AssetRef ref = waveRef;
                    SampleLibrary.executor().execute(() -> {
                        try {
                            float[] peaks = SampleLibrary.resolve(ref).peaks(64);
                            net.minecraft.client.Minecraft.getInstance().execute(() -> { if (ref.equals(waveRef)) waveform = peaks; });
                        } catch (RuntimeException ignored) { /* Inspector status explains unavailable assets. */ }
                    });
                }
                if (waveform != null) for (int i = 0; i < waveform.length; i++) {
                    int h = (int) (waveform[i] * 12);
                    graphics.fill(x + i * 2, 104 - h, x + i * 2 + 1, 105 + h, 0xff77bbdd);
                }
            } else if (node.type().equals("euclid")) {
                int steps = (int) Math.round(node.params().getOrDefault("steps", 16.0));
                int pulses = (int) Math.round(node.params().getOrDefault("pulses", 4.0));
                int rotation = (int) Math.round(node.params().getOrDefault("rotation", 0.0));
                steps = Math.max(1, Math.min(64, steps));
                pulses = Math.max(0, Math.min(steps, pulses));
                boolean[] stepBits = new boolean[steps];
                int shift = Math.floorMod(rotation, steps);
                for (int s = 0; s < steps; s++) {
                    int index = Math.floorMod(s - shift, steps);
                    stepBits[s] = (index * pulses % steps < pulses);
                }
                renderer.drawEuclidRing(graphics, x + 65, 75, stepBits);
            }
            int row = 0;
            for (var param : EditorState.displayParams(node).entrySet()) {
                graphics.drawString(font, String.format(java.util.Locale.ROOT, "%s: %.2f", param.getKey(), param.getValue()), textX, 124 + row++ * 14, textColor, false);
            }
            graphics.drawString(font, "Drag values / Delete key", textX, 198, textColor, false);
        }
        renderer.drawPanel(graphics, PanelKind.TRANSPORT, 0, height - 20, width, 20);
        graphics.drawString(font, font.plainSubstrByWidth(message, width - 8), 8, height - 14, textColor, false);
        graphics.drawString(font, String.format(java.util.Locale.ROOT, "Cycle %.2f  %s", cycle, live != null && live.playing() ? "Playing" : "Stopped"), 249, 8, textColor, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (draggedSample != null && sampleDragging) graphics.drawString(font, draggedSample.assetId(), mouseX + 8, mouseY, 0xffcc66, false);
        if (state.isQuickSpawnOpen()) {
            Vec2 screen = state.toScreen(state.quickSpawnAt());
            int popupW = 120, popupH = 20 + SPAWNABLE_TYPES.size() * 16;
            int px = (int) Math.max(10, Math.min(width - popupW - 10, screen.x()));
            int py = (int) Math.max(26, Math.min(height - popupH - 25, screen.y()));
            renderer.drawPanel(graphics, PanelKind.DRAWER, px, py, popupW, popupH);
            graphics.drawString(font, "Quick Spawn", px + 8, py + 8, textColor, false);
            for (int i = 0; i < SPAWNABLE_TYPES.size(); i++) {
                int iy = py + 18 + i * 16;
                boolean hovered = mouseX >= px && mouseX < px + popupW && mouseY >= iy && mouseY < iy + 16;
                if (hovered || i == quickSpawnSelected) graphics.fill(px + 2, iy, px + popupW - 2, iy + 14, 0x40ffffff);
                graphics.drawString(font, SPAWNABLE_TYPES.get(i), px + 8, iy + 3, (hovered || i == quickSpawnSelected) ? 0xffffff : textColor, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        search.setFocused(false); bpm.setFocused(false); setFocused(null);
        if (state.isQuickSpawnOpen()) {
            Vec2 screen = state.toScreen(state.quickSpawnAt());
            int popupW = 120, popupH = 20 + SPAWNABLE_TYPES.size() * 16;
            int px = (int) Math.max(10, Math.min(width - popupW - 10, screen.x()));
            int py = (int) Math.max(26, Math.min(height - popupH - 25, screen.y()));
            if (mouseX >= px && mouseX < px + popupW && mouseY >= py + 18 && mouseY < py + popupH) {
                int index = (int) ((mouseY - (py + 18)) / 16);
                if (index >= 0 && index < SPAWNABLE_TYPES.size()) {
                    spawnChosenNode(SPAWNABLE_TYPES.get(index));
                    return true;
                }
            }
            state.closeQuickSpawn();
            return true;
        }
        if (mouseY < 24 || mouseY >= height - 20) return true;
        if (drawer && mouseX < DRAWER_WIDTH) {
            browsing = true;
            int row = scroll + (int) ((mouseY - 64) / 16);
            var rows = samples();
            if (button == 0 && mouseY >= 64 && row >= 0 && row < rows.size()) {
                selectedSample = row; draggedSample = rows.get(row).ref(); sampleDragging = false;
                sampleDownX = mouseX; sampleDownY = mouseY;
                SampleCommands.audition(draggedSample, s -> message = s);
            }
            return true;
        }
        if (!state.selection().isEmpty() && mouseX >= Math.max(DRAWER_WIDTH + 6, width - 154) && mouseY < 220) {
            var node = state.node(state.selection().iterator().next());
            int row = (int) ((mouseY - 124) / 14);
            var displayParams = EditorState.displayParams(node);
            if (button == 0 && mouseY >= 124 && row < displayParams.size()) {
                String param = displayParams.keySet().stream().skip(row).findFirst().orElseThrow();
                input.startValueDrag(node.id(), param, mouseY, true);
                return true;
            }
            // Inside the inspector panel's bounds but not on an actual param row (e.g. the
            // euclid ring, waveform, or empty space below the params) -- fall through instead
            // of silently swallowing the click, otherwise nothing under the panel is ever
            // clickable again once any node is selected.
        }
        browsing = false;
        input.mouseDown(mouseButton(button), mouseX, mouseY, hasControlDown());
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggedSample != null) { sampleDragging |= Math.hypot(mouseX - sampleDownX, mouseY - sampleDownY) > 4; return true; }
        if (mouseY < 24 || drawer && mouseX < DRAWER_WIDTH) return true;
        input.mouseDrag(mouseX, mouseY, dragX, dragY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggedSample != null) {
            if (sampleDragging && (!drawer || mouseX >= DRAWER_WIDTH) && mouseY >= 24 && mouseY < height - 20) {
                Vec2 world = state.toWorld(mouseX, mouseY);
                String target = state.nodes().stream().filter(n -> state.layout().get(n.id()) != null && NodeGeometry.containsBody(state.layout().get(n.id()), world)).map(Graph.Node::id).findFirst().orElse(null);
                try { state.dropSample(draggedSample, world, target); message = "Draft changed. Connect new nodes, then Apply."; }
                catch (IllegalArgumentException error) { message = error.getMessage(); }
            }
            draggedSample = null; sampleDragging = false; return true;
        }
        input.mouseUp();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (drawer && mouseX < DRAWER_WIDTH) { scroll -= (int) Math.signum(scrollY); clampScroll(samples().size()); return true; }
        input.mouseScroll(scrollY, mouseX, mouseY);
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        input.mouseMoved(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_B && hasControlDown()) { drawer = !drawer; search.visible = drawer; search.setFocused(false); return true; }
        if (state.isQuickSpawnOpen()) {
            if (keyCode == GLFW.GLFW_KEY_UP) { quickSpawnSelected = Math.max(0, quickSpawnSelected - 1); return true; }
            if (keyCode == GLFW.GLFW_KEY_DOWN) { quickSpawnSelected = Math.min(SPAWNABLE_TYPES.size() - 1, quickSpawnSelected + 1); return true; }
            if (keyCode == GLFW.GLFW_KEY_ENTER) { spawnChosenNode(SPAWNABLE_TYPES.get(quickSpawnSelected)); return true; }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { state.closeQuickSpawn(); return true; }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (SampleCommands.auditioning()) { SampleCommands.stop(); browsing = true; return true; }
            if (search.isFocused() || bpm.isFocused()) { search.setFocused(false); bpm.setFocused(false); setFocused(null); return true; }
            if (input.handleEscape(false, false) == InputController.EscapeConsumer.PASSTHROUGH) onClose();
            return true;
        }
        if (search.isFocused() || bpm.isFocused()) return super.keyPressed(keyCode, scanCode, modifiers);
        if (browsing && drawer && (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE)) {
            var rows = samples();
            if (!rows.isEmpty()) {
                selectedSample = Math.max(0, Math.min(rows.size() - 1, selectedSample + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : keyCode == GLFW.GLFW_KEY_UP ? -1 : 0)));
                if (selectedSample < scroll) scroll = selectedSample;
                if (selectedSample >= scroll + visibleRows()) scroll = selectedSample - visibleRows() + 1;
                if (keyCode == GLFW.GLFW_KEY_ENTER && SampleCommands.auditioning()) SampleCommands.stop();
                else SampleCommands.audition(rows.get(selectedSample).ref(), s -> message = s);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) input.spaceKeyChanged(true);
        return fromKeyCode(keyCode).map(key -> input.keyDown(key, hasControlDown(), hasShiftDown(), false)).orElse(false);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) input.spaceKeyChanged(false);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public Graph currentGraph() { return state.toGraph(); }
    /** Same live-session check render() uses for the "Cycle .. Playing/Stopped" readout,
     *  so the Play/Stop button's icon actually reflects transport state rather than always
     *  showing the same glyph. */
    private String playStateIcon() {
        var snapshot = MusicClient.snapshot();
        var live = snapshot == null ? null : snapshot.at(MusicClient.serverNow());
        return live != null && live.playing() ? "icon_stop" : "icon_play";
    }
    private int visibleRows() { return Math.max(1, (height - 88) / 16); }
    private void clampScroll(int size) { scroll = Math.max(0, Math.min(scroll, Math.max(0, size - visibleRows()))); }
    private List<SampleCatalog.Entry> samples() {
        String query = search == null ? "" : search.getValue().toLowerCase(java.util.Locale.ROOT).trim();
        return SampleLibrary.catalog().entries().stream().filter(e -> {
            String id = e.ref().assetId();
            for (String token : query.split("\\s+")) {
                if (token.equals("@custom")) { if (!id.startsWith("custom:")) return false; }
                else if (token.equals("@factory")) { if (!id.startsWith("factory:")) return false; }
                else if (!id.contains(token)) return false;
            }
            return true;
        }).toList();
    }
    private void reloadSession() {
        if (request != null) return;
        var desired = MusicClient.desiredState();
        if (desired == null) { message = "No live session"; return; }
        if (!state.toGraph().equals(baseGraph) && !message.equals("Reload again to discard your local draft.")) { message = "Reload again to discard your local draft."; return; }
        state.loadGraph(desired.graph()); baseGraph = desired.graph(); baseEpoch = MusicClient.epoch(); baseRevision = MusicClient.snapshot().revision();
        bpm.setValue(Double.toString(desired.bpm())); message = "Loaded server revision " + baseRevision;
    }
    private void submit(boolean toggle) {
        if (request != null) { message = "Waiting for server acknowledgement"; return; }
        if (baseEpoch == null || !baseEpoch.equals(MusicClient.epoch()) || !ClientPlayNetworking.canSend(MusicPackets.Submit.TYPE)) { message = "Session unavailable or changed; reload"; return; }
        try {
            var graph = state.toGraph();
            String json = GraphJson.encode(graph);
            GraphJson.decode(json);
            double tempo = Double.parseDouble(bpm.getValue());
            if (!Double.isFinite(tempo) || tempo < 30 || tempo > 300) throw new IllegalArgumentException("BPM must be 30–300");
            var desired = MusicClient.desiredState();
            if (desired == null) {
                message = "Session unavailable; reload";
                return;
            }
            boolean playing = desired.playing();
            UUID ticket = UUID.randomUUID();
            ClientPlayNetworking.send(new MusicPackets.Submit(baseEpoch, ticket, baseRevision, json, tempo, toggle ? !playing : playing));
            request = ticket; submittedAt = System.nanoTime(); submittedGraph = graph; message = "Submitting…";
        } catch (IllegalArgumentException error) { message = "Draft not applied: " + error.getMessage(); }
    }
    public void submissionResult(MusicPackets.SubmitResult result) {
        if (!result.request().equals(request)) return;
        request = null; message = result.message();
        if (result.accepted()) { baseRevision++; baseGraph = submittedGraph; }
    }
    @Override public void removed() { SampleCommands.stop(); super.removed(); }
    @Override public void onClose() {
        if (request != null) { message = "Wait for the submission result before closing."; return; }
        if (!state.toGraph().equals(baseGraph) && !confirmClose) {
            confirmClose = true; message = "Unsent draft: Apply to keep it, or Escape again to discard."; return;
        }
        super.onClose();
    }

    private void spawnChosenNode(String type) {
        int count = 1;
        String id = type + "_" + count;
        while (state.node(id) != null) { count++; id = type + "_" + count; }
        state.spawnNode(id, type);
        message = "Spawned " + type + " (" + id + ")";
    }

    private static InputController.Button mouseButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> InputController.Button.SECONDARY;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> InputController.Button.MIDDLE;
            default -> InputController.Button.PRIMARY;
        };
    }

    private static Optional<InputController.Key> fromKeyCode(int keyCode) {
        return Optional.ofNullable(switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> InputController.Key.SPACE;
            case GLFW.GLFW_KEY_DELETE -> InputController.Key.DELETE;
            case GLFW.GLFW_KEY_BACKSPACE -> InputController.Key.BACKSPACE;
            case GLFW.GLFW_KEY_TAB -> InputController.Key.TAB;
            case GLFW.GLFW_KEY_F -> InputController.Key.F;
            case GLFW.GLFW_KEY_Z -> InputController.Key.Z;
            case GLFW.GLFW_KEY_Y -> InputController.Key.Y;
            case GLFW.GLFW_KEY_C -> InputController.Key.C;
            case GLFW.GLFW_KEY_A -> InputController.Key.A;
            default -> null;
        });
    }
}

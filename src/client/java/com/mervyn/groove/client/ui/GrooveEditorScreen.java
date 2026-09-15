package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.NoOpThemeRenderer;
import com.mervyn.groove.client.ui.theme.PanelKind;
import com.mervyn.groove.client.ui.theme.PortState;
import com.mervyn.groove.client.ui.theme.ThemeRenderer;
import groove.engine.Graph;
import groove.engine.NodeType;
import groove.engine.NodeParam;
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
    private com.mervyn.groove.music.EditorPackets.State blockSession;
    private UUID blockRequest;
    private long blockSent;
    private int blockAction;
    private boolean commitAfterSave;
    private Graph blockSubmitted;
    private double blockSubmittedBpm;
    private double savedBpm;
    private long blockAnchor;
    private boolean accessOpen;
    private UUID allowlistRequest;
    private long allowlistSent;
    private int accessScroll;
    private boolean isOwner;
    private String ownerName = "";
    private List<String> editorNames = List.of();
    private EditBox allowlistName;
    private ThemedButton allowlistAdd, allowlistRemove;
    private boolean speakersOpen;
    private UUID speakerListRequest;
    private long speakerListSent;
    private UUID speakerBindRequest;
    private long speakerBindSent;
    private int speakerScroll;
    private List<net.minecraft.core.BlockPos> speakerPositions = List.of();
    private List<Boolean> speakerLinked = List.of();
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
    private static final List<NodeType> SPAWNABLE_TYPES = java.util.Arrays.stream(NodeType.values())
            .filter(t -> t != NodeType.GENERATOR_SAMPLE).toList();
    private int quickSpawnSelected;
    private AssetRef waveRef;
    private float[] waveform;
    private boolean confirmClose;
    private int knobScroll;
    private String knobNode;
    private String focusedKnobParam;
    private EditBox knobEntry;
    private String knobEntryNode, knobEntryParam;
    private final SampleSearch sampleSearch = new SampleSearch();
    private final java.util.Set<String> collapsedFolders = new java.util.HashSet<>();

    public GrooveEditorScreen(Graph graph) { this(graph, new NoOpThemeRenderer()); }

    public GrooveEditorScreen(Graph graph, ThemeRenderer renderer) {
        super(Component.literal("Groove"));
        state = new EditorState(graph);
        input = new InputController(state);
        this.renderer = renderer;
        baseGraph = new Graph(Graph.CURRENT_VERSION, graph.nodes(), graph.edges()); baseEpoch = MusicClient.epoch();
        baseRevision = MusicClient.snapshot() == null ? -1 : MusicClient.snapshot().revision();
        state.onTogglePlay(() -> submit(true));
        state.onGraphChanged(() -> confirmClose = false);
    }

    public GrooveEditorScreen(Graph graph, ThemeRenderer renderer, com.mervyn.groove.music.EditorPackets.State session) {
        this(graph, renderer);
        blockSession = session;
        savedBpm = session.bpm();
        blockAnchor = MusicClient.serverNow();
        message = "Draft saves automatically. Commit publishes it.";
    }

    private boolean blockDirty() {
        if (!state.toGraph().equals(baseGraph)) return true;
        if (bpm == null) return false;
        try { return Double.parseDouble(bpm.getValue()) != savedBpm; }
        catch (NumberFormatException error) { return true; }
    }
    private void sendBlock(int action, boolean toggle) {
        if (blockRequest != null) return;
        // Throttle attempts even when BPM validation fails (for example while the
        // field is empty during typing), or render() retries on every frame.
        blockSent = System.nanoTime();
        try {
            double tempo = Double.parseDouble(bpm.getValue());
            if (!Double.isFinite(tempo) || tempo < 30 || tempo > 300) throw new IllegalArgumentException("BPM must be 30–300");
            blockSubmitted = state.toGraph();
            blockSubmittedBpm = tempo;
            blockRequest = UUID.randomUUID();
            blockSent = System.nanoTime();
            blockAction = action;
            ClientPlayNetworking.send(new com.mervyn.groove.music.EditorPackets.Request(blockSession.pos(), blockSession.session(), blockRequest,
                    action, blockSession.revision(), action == 1 ? GraphJson.encode(blockSubmitted) : "", tempo,
                    toggle ? !blockSession.playing() : blockSession.playing()));
        } catch (IllegalArgumentException error) { blockRequest = null; commitAfterSave = false; message = error.getMessage(); }
    }
    public void blockState(com.mervyn.groove.music.EditorPackets.State packet) {
        if (blockSession == null || !packet.request().equals(blockRequest)) return;
        blockRequest = null;
        message = packet.message();
        if (!packet.session().equals(blockSession.session())) { message = "Session replaced; reopen the editor"; commitAfterSave = false; return; }
        if (!packet.accepted()) { commitAfterSave = false; return; }
        boolean dirty = blockDirty();
        if (blockSession.playing() != packet.playing() || blockSession.bpm() != packet.bpm()) blockAnchor = MusicClient.serverNow();
        blockSession = packet;
        if (blockAction == 1) {
            baseGraph = blockSubmitted;
            savedBpm = blockSubmittedBpm;
        } else if (!dirty) {
            var received = GraphJson.decodeDraft(packet.graph());
            var graph = new Graph(Graph.CURRENT_VERSION, received.nodes(), received.edges());
            if (!state.toGraph().equals(graph)) state.loadGraph(graph);
            baseGraph = state.toGraph(); savedBpm = packet.bpm(); bpm.setValue(Double.toString(savedBpm));
        }
    }
    public void allowlistState(com.mervyn.groove.music.EditorPackets.AllowlistState packet) {
        if (blockSession == null || !packet.pos().equals(blockSession.pos()) || !packet.session().equals(blockSession.session())) return;
        if (packet.request().equals(allowlistRequest)) { allowlistRequest = null; blockSent = System.nanoTime(); }
        if (!packet.accepted()) { message = packet.message(); return; }
        isOwner = packet.owner(); ownerName = packet.ownerName(); editorNames = packet.editors();
        accessScroll = Math.min(accessScroll, Math.max(0, editorNames.size() - accessRows()));
        if (allowlistName != null) layoutAllowlist();
        if (!packet.message().isEmpty()) message = packet.message();
    }
    private void sendAllowlist(boolean allow) {
        if (blockSession == null || allowlistRequest != null || !isOwner) return;
        if (blockRequest != null || System.nanoTime() - blockSent < 100_000_000L) { message = "Please wait for the current editor request"; return; }
        String name = allowlistName.getValue().trim();
        if (name.isEmpty()) { message = "Enter a player name"; return; }
        allowlistRequest = UUID.randomUUID();
        allowlistSent = System.nanoTime();
        ClientPlayNetworking.send(new com.mervyn.groove.music.EditorPackets.AllowlistRequest(blockSession.pos(), blockSession.session(), allowlistRequest, name, allow));
    }
    private void fetchSpeakers() {
        if (blockSession == null) return;
        long now = System.nanoTime();
        if (speakerListRequest != null && now - speakerListSent < 2_000_000_000L) return;
        speakerListRequest = UUID.randomUUID();
        speakerListSent = now;
        ClientPlayNetworking.send(new com.mervyn.groove.music.SpeakerPackets.ListRequest(blockSession.pos(), blockSession.session(), speakerListRequest));
    }
    public void speakerListState(com.mervyn.groove.music.SpeakerPackets.ListState packet) {
        if (!packet.request().equals(speakerListRequest)) return;
        speakerListRequest = null;
        if (!packet.accepted()) { message = packet.message(); return; }
        speakerPositions = packet.speakers(); speakerLinked = packet.linked();
        speakerScroll = Math.min(speakerScroll, Math.max(0, speakerPositions.size() - accessRows()));
    }
    private void sendSpeakerBind(int row) {
        if (blockSession == null || speakerBindRequest != null || speakerListRequest != null || row < 0 || row >= speakerPositions.size()) return;
        speakerBindRequest = UUID.randomUUID();
        speakerBindSent = System.nanoTime();
        boolean link = !speakerLinked.get(row);
        ClientPlayNetworking.send(new com.mervyn.groove.music.SpeakerPackets.BindRequest(blockSession.pos(), blockSession.session(),
                speakerPositions.get(row), link, speakerBindRequest));
    }
    public void speakerBindState(com.mervyn.groove.music.SpeakerPackets.BindState packet) {
        if (!packet.request().equals(speakerBindRequest)) return;
        speakerBindRequest = null;
        message = packet.message();
        if (packet.accepted()) { speakerListRequest = null; fetchSpeakers(); }
    }
    @Override
    protected void init() {
        input.setViewport(width, height);
        String theme = renderer.themeName();
        int textColor = renderer.textColor();
        addRenderableWidget(new ThemedButton(4, 2, 46, 20, Component.literal(blockSession == null ? "Apply" : "Commit"), font, theme, textColor, b -> submit(false)));
        addRenderableWidget(new ThemedButton(54, 2, 79, 20, Component.literal("Play/Stop"), font, theme, textColor, b -> submit(true), this::playStateIcon));
        addRenderableWidget(new ThemedButton(137, 2, 52, 20, Component.literal("Reload"), font, theme, textColor, b -> reloadSession()));
        String tempo = bpm == null ? Double.toString(blockSession != null ? blockSession.bpm() : MusicClient.desiredState() == null ? 128 : MusicClient.desiredState().bpm()) : bpm.getValue();
        bpm = new EditBox(font, 193, 2, 48, 20, Component.literal("BPM")); bpm.setMaxLength(7); bpm.setValue(tempo); addRenderableWidget(bpm);
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, 5, 40, DRAWER_WIDTH - 10, 18, Component.literal("Search samples"));
        search.setMaxLength(160); search.setValue(query); search.setResponder(s -> { scroll = 0; selectedSample = 0; });
        search.visible = drawer; addRenderableWidget(search);

        ThemedButton access = new ThemedButton(245, 2, 50, 20, Component.literal("Access"), font, theme, textColor,
                b -> { accessOpen = !accessOpen; speakersOpen = false; if (accessOpen) { state.clearSelection(); hideKnobEntry(); } layoutAllowlist(); });
        access.visible = blockSession != null; addRenderableWidget(access);
        ThemedButton speakers = new ThemedButton(299, 2, 64, 20, Component.literal("Speakers"), font, theme, textColor,
                b -> { speakersOpen = !speakersOpen; accessOpen = false; layoutAllowlist();
                    if (speakersOpen) { state.clearSelection(); hideKnobEntry(); fetchSpeakers(); } });
        speakers.visible = blockSession != null; addRenderableWidget(speakers);
        int x = Math.max(DRAWER_WIDTH + 10, width - 150) + 4;
        int bottom = Math.min(height - 20, 220);
        String name = allowlistName == null ? "" : allowlistName.getValue();
        allowlistName = new EditBox(font, x, bottom - 40, width - x - 8, 18, Component.literal("Player name"));
        allowlistName.setMaxLength(36); allowlistName.setValue(name); addRenderableWidget(allowlistName);
        allowlistAdd = new ThemedButton(x, bottom - 20, (width - x - 12) / 2, 18, Component.literal("Add"), font, theme, textColor, b -> sendAllowlist(true));
        addRenderableWidget(allowlistAdd);
        allowlistRemove = new ThemedButton(x + (width - x - 12) / 2 + 4, bottom - 20, (width - x - 12) / 2, 18, Component.literal("Remove"), font, theme, textColor, b -> sendAllowlist(false));
        addRenderableWidget(allowlistRemove);
        layoutAllowlist();
        // Shares allowlistName's slot: never visible together, since opening Access clears selection.
        String entryText = knobEntry == null ? "" : knobEntry.getValue();
        boolean entryVisible = knobEntry != null && knobEntry.visible;
        knobEntry = new EditBox(font, x, bottom - 40, width - x - 8, 18, Component.literal("Value"));
        knobEntry.setMaxLength(32); knobEntry.setValue(entryText); knobEntry.visible = entryVisible; addRenderableWidget(knobEntry);
        if (entryVisible) { setFocused(knobEntry); knobEntry.setFocused(true); }
    }
    private void layoutAllowlist() {
        boolean visible = accessOpen && blockSession != null;
        allowlistName.visible = visible && isOwner; allowlistAdd.visible = visible && isOwner; allowlistRemove.visible = visible && isOwner;
        if (!allowlistName.visible && allowlistName.isFocused()) { allowlistName.setFocused(false); setFocused(null); }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        state.tick();
        validateKnobEntryTarget();
        if (speakerBindRequest != null && System.nanoTime() - speakerBindSent > 5_000_000_000L) {
            speakerBindRequest = null; message = "Speaker request timed out; refresh before retrying";
        }
        if (speakersOpen && speakerBindRequest == null && System.nanoTime() - speakerListSent > 2_000_000_000L) fetchSpeakers();
        if (blockSession != null) {
            long elapsed = System.nanoTime() - blockSent;
            if (blockRequest != null && elapsed > 5_000_000_000L) { blockRequest = null; commitAfterSave = false; message = "Editor request timed out"; }
            if (allowlistRequest != null && System.nanoTime() - allowlistSent > 15_000_000_000L) {
                allowlistRequest = null; message = "Allowlist request timed out; try again";
            }
            if (blockRequest == null && allowlistRequest == null && elapsed > 400_000_000L) {
                if (blockDirty()) sendBlock(1, false);
                else if (commitAfterSave) { commitAfterSave = false; sendBlock(2, false); }
                else if (elapsed > 1_000_000_000L) sendBlock(0, false);
            }
        }
        renderer.tickDecorative(partialTick);
        // Use the same server clock domain as playback, including scheduled transitions.
        var snapshot = MusicClient.snapshot();
        var live = blockSession != null
                ? new groove.engine.SessionState(blockSession.revision(), blockAnchor, 0, blockSession.bpm(), blockSession.playing(), state.toGraph())
                : snapshot == null ? null : snapshot.at(MusicClient.serverNow());
        double cycle = live == null ? 0 : live.cycleAt(MusicClient.serverNow());
        float tempoPhase = (float) (cycle - Math.floor(cycle));
        renderer.drawBackground(graphics, width, height);
        renderer.drawPanel(graphics, PanelKind.TRANSPORT, 0, 0, width, 24);

        for (Graph.Edge edge : state.edges()) {
            Vec2 from = state.layout().get(edge.fromNode());
            Vec2 to = state.layout().get(edge.toNode());
            if (from == null || to == null) continue;
            Vec2 fromScreen = state.toScreen(NodeGeometry.port(from, state.node(edge.fromNode()).type(), edge.fromPort(), true));
            Vec2 toScreen = state.toScreen(NodeGeometry.port(to, state.node(edge.toNode()).type(), edge.toPort(), false));
            renderer.drawCable(graphics, new CableView(fromScreen, toScreen, tempoPhase), tempoPhase);
        }
        if (state.isWireDragging()) {
            EditorState.WireDrag drag = state.wireDrag();
            Vec2 from = state.layout().get(drag.fromNode());
            if (from != null) {
                Vec2 fromScreen = state.toScreen(NodeGeometry.port(from, state.node(drag.fromNode()).type(), drag.fromPort(), true));
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
            graphics.drawString(font, font.plainSubstrByWidth(node.type().idStem(), 136), 8, 30, renderer.textColor(), false);
            graphics.pose().popPose();
            for (var socket : node.type().outputPorts()) {
                Vec2 port = state.toScreen(NodeGeometry.port(origin, node.type(), socket.name(), true));
                renderer.drawPort(graphics, (int) port.x(), (int) port.y(), PortState.FREE);
                if (port.distanceTo(new Vec2(mouseX,mouseY)) < 9)
                    graphics.renderTooltip(font, Component.literal(socket.name()+" · "+socket.type().name()),mouseX,mouseY);
            }
            for (var socket : node.type().inputPorts()) {
                Vec2 port = state.toScreen(NodeGeometry.port(origin, node.type(), socket.name(), false));
                renderer.drawPort(graphics, (int) port.x(), (int) port.y(), PortState.FREE);
                if (port.distanceTo(new Vec2(mouseX,mouseY)) < 9)
                    graphics.renderTooltip(font, Component.literal(socket.name()+" · "+socket.type().name()),mouseX,mouseY);
            }
        }
        if (request != null && System.nanoTime() - submittedAt > 10_000_000_000L) {
            request = null; message = "No acknowledgement. Reload before retrying; your draft is still here.";
        }
        int textColor = renderer.textColor();
        if (drawer) {
            renderer.drawPanel(graphics, PanelKind.DRAWER, 0, 24, DRAWER_WIDTH, height - 44);
            graphics.drawString(font, "Sample Vault  (Ctrl+B)", 8, 32, textColor, false);
            var rows = drawerRows(); clampScroll(rows.size());
            for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
                int y = 64 + (i - scroll) * 16;
                if (i == selectedSample) graphics.fill(2, y - 2, DRAWER_WIDTH - 2, y + 13, 0x5000e5ff);
                var row = rows.get(i);
                String label; int indent;
                if (row instanceof SampleTree.Folder folder) {
                    label = (folder.collapsed() ? "+ " : "- ") + folder.label() + " (" + folder.count() + ")";
                    indent = 8 + folder.depth() * 8;
                } else {
                    var leaf = (SampleTree.Leaf) row;
                    label = (leaf.favorite() ? "* " : "  ") + SampleTree.leafName(leaf.entry());
                    indent = 8 + leaf.depth() * 8;
                }
                graphics.drawString(font, font.plainSubstrByWidth(label, DRAWER_WIDTH - indent - 5), indent, y, textColor, false);
            }
        }
        if (accessOpen && blockSession != null) {
            renderAccessPanel(graphics, textColor);
        } else if (speakersOpen && blockSession != null) {
            renderSpeakerPanel(graphics, textColor);
        } else if (!state.selection().isEmpty()) {
            var node = state.node(state.selection().iterator().next());
            int x = Math.max(DRAWER_WIDTH + 10, width - 150);
            int inspectorBottom = Math.min(height - 20, 220);
            renderer.drawPanel(graphics, PanelKind.DRAWER, x - 4, 26, width - (x - 4), inspectorBottom - 26);
            // Text sits 4px further right / 8px lower than the panel's own origin, past its
            // 6px drawn border. Same reasoning as the node card and drawer text below.
            int textX = x + 4;
            graphics.drawString(font, font.plainSubstrByWidth("Inspector: " + node.id(), Math.max(0, inspectorCloseX() - textX - 6)), textX, 34, textColor, false);
            graphics.drawString(font, "x", inspectorCloseX(), 34, textColor, false);
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
            } else if (node.type() == NodeType.EUCLID) {
                int steps = (int) Math.round(node.params().getOrDefault(NodeParam.STEPS, 16.0));
                int pulses = (int) Math.round(node.params().getOrDefault(NodeParam.PULSES, 4.0));
                int rotation = (int) Math.round(node.params().getOrDefault(NodeParam.ROTATION, 0.0));
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
            graphics.enableScissor(x, knobTop(node), width - 4, knobBottom());
            for (var knob : knobs(node)) {
                knob.draw(graphics, font, renderer.themeName(), textColor, node,
                        EditorState.displayParams(node).get(knob.param()), mouseX, mouseY,
                        knob.param().equals(focusedKnobParam));
            }
            graphics.disableScissor();
            graphics.drawString(font, "Drag / Ctrl: fine", textX, inspectorBottom - 12, textColor, false);
        }
        renderer.drawPanel(graphics, PanelKind.TRANSPORT, 0, height - 20, width, 20);
        graphics.drawString(font, font.plainSubstrByWidth(message, width - 8), 8, height - 14, textColor, false);
        graphics.drawString(font, String.format(java.util.Locale.ROOT, "Cycle %.2f  %s", cycle, live != null && live.playing() ? "Playing" : "Stopped"), 249, 8, textColor, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (draggedSample != null && sampleDragging) graphics.drawString(font, draggedSample.assetId(), mouseX + 8, mouseY, 0xffcc66, false);
        if (state.isQuickSpawnOpen()) {
            Vec2 screen = state.toScreen(state.quickSpawnAt());
            int rows = (SPAWNABLE_TYPES.size()+1)/2;
            int popupW = 240, popupH = 20 + rows * 16;
            int px = (int) Math.max(10, Math.min(width - popupW - 10, screen.x()));
            int py = (int) Math.max(26, Math.min(height - popupH - 25, screen.y()));
            renderer.drawPanel(graphics, PanelKind.DRAWER, px, py, popupW, popupH);
            graphics.drawString(font, "Quick Spawn", px + 8, py + 8, textColor, false);
            for (int i = 0; i < SPAWNABLE_TYPES.size(); i++) {
                int iy = py + 18 + (i%rows) * 16, ix = px + (i/rows)*120;
                boolean hovered = mouseX >= ix && mouseX < ix + 120 && mouseY >= iy && mouseY < iy + 16;
                if (hovered || i == quickSpawnSelected) graphics.fill(ix + 2, iy, ix + 118, iy + 14, 0x40ffffff);
                graphics.drawString(font, SPAWNABLE_TYPES.get(i).idStem(), ix + 8, iy + 3, (hovered || i == quickSpawnSelected) ? 0xffffff : textColor, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        search.setFocused(false); bpm.setFocused(false); setFocused(null);
        hideKnobEntry();
        if (state.isQuickSpawnOpen()) {
            Vec2 screen = state.toScreen(state.quickSpawnAt());
            int rows = (SPAWNABLE_TYPES.size()+1)/2;
            int popupW = 240, popupH = 20 + rows * 16;
            int px = (int) Math.max(10, Math.min(width - popupW - 10, screen.x()));
            int py = (int) Math.max(26, Math.min(height - popupH - 25, screen.y()));
            if (mouseX >= px && mouseX < px + popupW && mouseY >= py + 18 && mouseY < py + popupH) {
                int index = (int)((mouseX-px)/120)*rows + (int) ((mouseY - (py + 18)) / 16);
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
            var rows = drawerRows();
            if (mouseY >= 64 && row >= 0 && row < rows.size()) {
                selectedSample = row;
                var clicked = rows.get(row);
                if (clicked instanceof SampleTree.Folder folder) {
                    if (button == 0) toggleFolder(folder.path());
                } else {
                    var leaf = (SampleTree.Leaf) clicked;
                    if (button == 1) SampleFavorites.toggle(leaf.entry().ref().assetId());
                    else if (button == 0) {
                        draggedSample = leaf.entry().ref(); sampleDragging = false;
                        sampleDownX = mouseX; sampleDownY = mouseY;
                        SampleCommands.audition(draggedSample, s -> message = s);
                    }
                }
            }
            return true;
        }
        if (rightPanelActive() && insideInspector(mouseX, mouseY)) {
            if (button == 0 && mouseX >= inspectorCloseX() - 2 && mouseX < inspectorCloseX() + 8 && mouseY >= 32 && mouseY < 42) {
                if (accessOpen) { accessOpen = false; layoutAllowlist(); }
                else if (speakersOpen) { speakersOpen = false; }
                else state.clearSelection();
                return true;
            }
            if (accessOpen && button == 0 && isOwner && mouseY >= 60 && mouseY < 60 + accessRows() * 11) {
                int row = accessScroll + (int) ((mouseY - 60) / 11);
                if (row < editorNames.size()) allowlistName.setValue(editorNames.get(row));
            }
            if (speakersOpen && button == 0 && mouseY >= 60 && mouseY < 60 + accessRows() * 11) {
                int row = speakerScroll + (int) ((mouseY - 60) / 11);
                sendSpeakerBind(row);
            }
            if (!accessOpen && !speakersOpen) {
                var node = state.node(state.selection().iterator().next());
                browsing = false;
                if (button == 0 && mouseY >= knobTop(node) && mouseY < knobBottom()) {
                    knobs(node).stream().filter(knob -> knob.contains(mouseX, mouseY)).findFirst()
                            .ifPresent(knob -> {
                                focusedKnobParam = knob.param();
                                input.startValueDrag(node.id(), knob.param(), mouseY, hasControlDown());
                            });
                }
            }
            // Any other click inside this panel (euclid ring, waveform, allowlist/speaker rows,
            // empty space) is swallowed here rather than falling through to the canvas underneath.
            return true;
        }
        browsing = false;
        input.mouseDown(mouseButton(button), mouseX, mouseY, hasControlDown());
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggedSample != null) { sampleDragging |= Math.hypot(mouseX - sampleDownX, mouseY - sampleDownY) > 4; return true; }
        input.mouseDrag(mouseX, mouseY, dragX, dragY, this::canvasVisible);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggedSample != null) {
            if (sampleDragging && (!drawer || mouseX >= DRAWER_WIDTH) && mouseY >= 24 && mouseY < height - 20
                    && (!rightPanelActive() || !insideInspector(mouseX, mouseY))) {
                Vec2 world = state.toWorld(mouseX, mouseY);
                String target = state.nodes().stream().filter(n -> state.layout().get(n.id()) != null && NodeGeometry.containsBody(state.layout().get(n.id()), world)).map(Graph.Node::id).findFirst().orElse(null);
                try { state.dropSample(draggedSample, world, target); message = "Draft changed. Connect new nodes, then Apply."; }
                catch (IllegalArgumentException error) { message = error.getMessage(); }
            }
            draggedSample = null; sampleDragging = false; return true;
        }
        input.mouseUp(mouseX, mouseY, this::canvasVisible);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (accessOpen && insideInspector(mouseX, mouseY)) {
            accessScroll = Math.max(0, Math.min(Math.max(0, editorNames.size() - accessRows()), accessScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (speakersOpen && insideInspector(mouseX, mouseY)) {
            speakerScroll = Math.max(0, Math.min(Math.max(0, speakerPositions.size() - accessRows()), speakerScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (!state.selection().isEmpty() && insideInspector(mouseX, mouseY)) {
            if (!state.isValueDragging()) {
                var node = state.node(state.selection().iterator().next());
                clampKnobScroll(node); // Reset scrolling when the selected node changes.
                knobScroll += (int) (-Math.signum(scrollY) * RotaryKnob.HEIGHT);
                clampKnobScroll(node); // Clamp to the available content.
            }
            return true;
        }
        if (drawer && mouseX < DRAWER_WIDTH) { scroll -= (int) Math.signum(scrollY); clampScroll(drawerRows().size()); return true; }
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
        validateKnobEntryTarget();
        // A focused name field owns editing keys; canvas shortcuts must not consume
        // Backspace, Delete, arrows, or undo while the user edits a player name.
        if (allowlistName != null && allowlistName.visible && allowlistName.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { allowlistName.setFocused(false); setFocused(null); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (knobEntry != null && knobEntry.visible && knobEntry.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { hideKnobEntry(); return true; }
            if (keyCode == GLFW.GLFW_KEY_ENTER) { commitKnobEntry(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
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
            var rows = drawerRows();
            if (!rows.isEmpty()) {
                selectedSample = Math.max(0, Math.min(rows.size() - 1, selectedSample + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : keyCode == GLFW.GLFW_KEY_UP ? -1 : 0)));
                if (selectedSample < scroll) scroll = selectedSample;
                if (selectedSample >= scroll + visibleRows()) scroll = selectedSample - visibleRows() + 1;
                var selected = rows.get(selectedSample);
                if (selected instanceof SampleTree.Leaf leaf) {
                    if (keyCode == GLFW.GLFW_KEY_ENTER && SampleCommands.auditioning()) SampleCommands.stop();
                    else SampleCommands.audition(leaf.entry().ref(), s -> message = s);
                } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                    toggleFolder(((SampleTree.Folder) selected).path());
                }
            }
            return true;
        }
        if (!accessOpen && !speakersOpen && !state.isQuickSpawnOpen() && !state.selection().isEmpty()
                && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                    || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_ENTER)) {
            var node = state.node(state.selection().iterator().next());
            clampKnobScroll(node);
            var params = new java.util.ArrayList<>(EditorState.displayParams(node).keySet());
            if (!params.isEmpty()) {
                boolean newlyFocused = focusedKnobParam == null || !params.contains(focusedKnobParam);
                if (newlyFocused) focusedKnobParam = params.get(0);
                int index = params.indexOf(focusedKnobParam);
                if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
                    focusedKnobParam = params.get(Math.max(0, Math.min(params.size() - 1, index + (newlyFocused ? 0 : keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1))));
                    scrollKnobIntoView(node, focusedKnobParam);
                } else if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
                    state.stepKnobValue(node.id(), focusedKnobParam, keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1, hasControlDown());
                } else {
                    openKnobEntry(node, focusedKnobParam);
                }
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) input.spaceKeyChanged(true);
        return fromKeyCode(keyCode).map(key -> input.keyDown(key, hasControlDown(), hasShiftDown(), false)).orElse(false);
    }
    private void commitKnobEntry() {
        try {
            double value = Double.parseDouble(knobEntry.getValue().trim());
            if (knobEntryNode != null && knobEntryParam != null && state.selection().contains(knobEntryNode))
                state.setKnobValue(knobEntryNode, knobEntryParam, value);
            hideKnobEntry();
        } catch (IllegalArgumentException invalid) { message = "Enter a finite number"; }
    }
    private void openKnobEntry(Graph.Node node, String param) {
        double value = EditorState.displayParams(node).getOrDefault(param, 0.0);
        knobEntryNode = node.id(); knobEntryParam = param;
        knobEntry.setValue(Double.toString(value));
        knobEntry.visible = true;
        setFocused(knobEntry);
        knobEntry.setFocused(true);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) input.spaceKeyChanged(false);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public Graph currentGraph() { return state.toGraph(); }
    private int knobTop(Graph.Node node) {
        return Math.min(node.sample() != null ? 120 : node.type() == NodeType.EUCLID ? 108 : 50,
                Math.max(50, knobBottom() - RotaryKnob.HEIGHT));
    }
    private int knobBottom() { return Math.max(50, Math.min(height - 20, 220) - 18); }
    private void clampKnobScroll(Graph.Node node) {
        if (!node.id().equals(knobNode)) { knobNode = node.id(); knobScroll = 0; focusedKnobParam = null; hideKnobEntry(); }
        int contentHeight = ((EditorState.displayParams(node).size() + 1) / 2) * RotaryKnob.HEIGHT;
        knobScroll = Math.max(0, Math.min(knobScroll, Math.max(0, contentHeight - (knobBottom() - knobTop(node)))));
    }
    private void validateKnobEntryTarget() {
        if (knobEntry == null || !knobEntry.visible) return;
        var node = knobEntryNode == null ? null : state.node(knobEntryNode);
        if (node == null || !state.selection().contains(knobEntryNode) || !EditorState.displayParams(node).containsKey(knobEntryParam))
            hideKnobEntry();
    }
    private void hideKnobEntry() {
        knobEntryNode = null; knobEntryParam = null;
        if (knobEntry == null || !knobEntry.visible) return;
        knobEntry.visible = false;
        if (knobEntry.isFocused()) { knobEntry.setFocused(false); setFocused(null); }
    }
    /** Scrolls the focused knob's row into view after Up/Down moves focus, same clamp
     *  {@link #clampKnobScroll} uses for the mouse wheel. */
    private void scrollKnobIntoView(Graph.Node node, String param) {
        var params = EditorState.displayParams(node);
        int index = new java.util.ArrayList<>(params.keySet()).indexOf(param);
        if (index < 0) return;
        int rowTop = (index / 2) * RotaryKnob.HEIGHT, rowBottom = rowTop + RotaryKnob.HEIGHT;
        int visible = knobBottom() - knobTop(node);
        if (rowTop < knobScroll) knobScroll = rowTop;
        else if (rowBottom - knobScroll > visible) knobScroll = rowBottom - visible;
        clampKnobScroll(node);
    }
    private List<RotaryKnob> knobs(Graph.Node node) {
        clampKnobScroll(node);
        var params = EditorState.displayParams(node);
        int x = Math.max(DRAWER_WIDTH + 10, width - 150) + 4;
        int cellWidth = Math.max(1, (width - x - 4) / 2);
        var result = new java.util.ArrayList<RotaryKnob>();
        int i = 0;
        for (String param : params.keySet()) {
            result.add(new RotaryKnob(param, x + (i % 2) * cellWidth,
                    knobTop(node) + (i / 2) * RotaryKnob.HEIGHT - knobScroll, cellWidth));
            i++;
        }
        return result;
    }
    private boolean canvasVisible(double x, double y) {
        return x >= 0 && x < width && y >= 24 && y < height - 20
                && (!drawer || x >= DRAWER_WIDTH)
                && (!rightPanelActive() || !insideInspector(x, y))
                && !state.isQuickSpawnOpen();
    }
    /** Shared with render()'s inspector panel drawing so the click-blocking bounds in
     *  mouseClicked never drift from what's actually drawn on screen. */
    private boolean insideInspector(double mouseX, double mouseY) {
        int x = Math.max(DRAWER_WIDTH + 10, width - 150);
        int inspectorBottom = Math.min(height - 20, 220);
        return mouseX >= x - 4 && mouseX < width && mouseY >= 26 && mouseY < inspectorBottom;
    }
    private int inspectorCloseX() { return width - 14; }
    private boolean rightPanelActive() { return accessOpen || speakersOpen || !state.selection().isEmpty(); }
    private int accessRows() { return Math.max(0, (Math.min(height - 20, 220) - 60 - 60) / 11); }
    private void renderAccessPanel(GuiGraphics graphics, int textColor) {
        int x = Math.max(DRAWER_WIDTH + 10, width - 150);
        int bottom = Math.min(height - 20, 220);
        renderer.drawPanel(graphics, PanelKind.DRAWER, x - 4, 26, width - (x - 4), bottom - 26);
        int textX = x + 4;
        graphics.drawString(font, "Access", textX, 34, textColor, false);
        graphics.drawString(font, "x", inspectorCloseX(), 34, textColor, false);
        graphics.drawString(font, font.plainSubstrByWidth("Owner: " + (ownerName.isEmpty() ? "(unclaimed)" : ownerName), width - textX - 8), textX, 48, textColor, false);
        int y = 60;
        if (editorNames.isEmpty()) graphics.drawString(font, "No editors added", textX, y, textColor, false);
        for (int i = accessScroll; i < Math.min(editorNames.size(), accessScroll + accessRows()); i++) {
            graphics.drawString(font, font.plainSubstrByWidth(editorNames.get(i), width - textX - 8), textX, y, textColor, false); y += 11;
        }
        String hint = isOwner ? "Click a name; scroll for more" : "Only owner can change access";
        graphics.drawString(font, font.plainSubstrByWidth(hint, width - textX - 8), textX, bottom - 54, 0xffcc66, false);
    }
    private void renderSpeakerPanel(GuiGraphics graphics, int textColor) {
        int x = Math.max(DRAWER_WIDTH + 10, width - 150);
        int bottom = Math.min(height - 20, 220);
        renderer.drawPanel(graphics, PanelKind.DRAWER, x - 4, 26, width - (x - 4), bottom - 26);
        int textX = x + 4;
        graphics.drawString(font, "Speakers", textX, 34, textColor, false);
        graphics.drawString(font, "x", inspectorCloseX(), 34, textColor, false);
        graphics.drawString(font, font.plainSubstrByWidth("Click to link/unlink", width - textX - 8), textX, 48, textColor, false);
        int y = 60;
        if (speakerListRequest != null && speakerPositions.isEmpty()) graphics.drawString(font, "Searching...", textX, y, textColor, false);
        else if (speakerPositions.isEmpty()) graphics.drawString(font, "No speakers within reach", textX, y, textColor, false);
        for (int i = speakerScroll; i < Math.min(speakerPositions.size(), speakerScroll + accessRows()); i++) {
            var pos = speakerPositions.get(i);
            String label = pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + (speakerLinked.get(i) ? "  [Linked]" : "");
            int color = speakerLinked.get(i) ? 0x55ff55 : textColor;
            graphics.drawString(font, font.plainSubstrByWidth(label, width - textX - 8), textX, y, color, false); y += 11;
        }
        graphics.drawString(font, font.plainSubstrByWidth("Click a row; scroll for more", width - textX - 8), textX, bottom - 54, 0xffcc66, false);
    }
    /** Same live-session check render() uses for the "Cycle .. Playing/Stopped" readout,
     *  so the Play/Stop button's icon actually reflects transport state rather than always
     *  showing the same glyph. */
    private String playStateIcon() {
        var snapshot = MusicClient.snapshot();
        var live = blockSession != null
                ? new groove.engine.SessionState(blockSession.revision(), blockAnchor, 0, blockSession.bpm(), blockSession.playing(), state.toGraph())
                : snapshot == null ? null : snapshot.at(MusicClient.serverNow());
        return live != null && live.playing() ? "icon_stop" : "icon_play";
    }
    private int visibleRows() { return Math.max(1, (height - 88) / 16); }
    private void clampScroll(int size) { scroll = Math.max(0, Math.min(scroll, Math.max(0, size - visibleRows()))); }
    private List<SampleCatalog.Entry> samples() {
        return sampleSearch.filter(SampleLibrary.catalog(), search == null ? "" : search.getValue());
    }
    /** While actively searching, folders are ignored entirely so a match anywhere in the
     *  catalog is never hidden behind a collapsed folder the user hasn't touched. */
    private List<SampleTree.Row> drawerRows() {
        String query = search == null ? "" : search.getValue();
        var collapsed = query.isBlank() ? collapsedFolders : java.util.Set.<String>of();
        return SampleTree.rows(samples(), SampleFavorites.all(), collapsed);
    }
    private void toggleFolder(String path) {
        if (!collapsedFolders.remove(path)) collapsedFolders.add(path);
    }
    private void reloadSession() {
        if (blockSession != null) {
            if (blockRequest != null) return;
            if (blockDirty() && !message.equals("Reload again to discard your local draft.")) { message = "Reload again to discard your local draft."; return; }
            state.loadGraph(GraphJson.decodeDraft(blockSession.graph())); baseGraph = state.toGraph();
            savedBpm = blockSession.bpm(); bpm.setValue(Double.toString(savedBpm));
            sendBlock(0, false); return;
        }
        if (request != null) return;
        var desired = MusicClient.desiredState();
        if (desired == null) { message = "No live session"; return; }
        if (!state.toGraph().equals(baseGraph) && !message.equals("Reload again to discard your local draft.")) { message = "Reload again to discard your local draft."; return; }
        state.loadGraph(desired.graph()); baseGraph = state.toGraph(); baseEpoch = MusicClient.epoch(); baseRevision = MusicClient.snapshot().revision();
        bpm.setValue(Double.toString(desired.bpm())); message = "Loaded server revision " + baseRevision;
    }
    private void submit(boolean toggle) {
        if (blockSession != null) {
            if (blockRequest != null) { message = "Waiting for draft acknowledgement"; return; }
            if (toggle) sendBlock(1, true);
            else { commitAfterSave = true; if (blockDirty()) sendBlock(1, false); }
            return;
        }
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
        if (blockSession != null) {
            if (blockRequest != null || commitAfterSave) { message = "Wait for the draft to save before closing"; return; }
            if (blockDirty() && !confirmClose) { confirmClose = true; message = "Unsaved draft: Escape again to discard local changes"; return; }
            super.onClose(); return;
        }
        if (request != null) { message = "Wait for the submission result before closing."; return; }
        if (!state.toGraph().equals(baseGraph) && !confirmClose) {
            confirmClose = true; message = "Unsent draft: Apply to keep it, or Escape again to discard."; return;
        }
        super.onClose();
    }

    private void spawnChosenNode(NodeType type) {
        int count = 1;
        String id = type.idStem() + "_" + count;
        while (state.node(id) != null) { count++; id = type.idStem() + "_" + count; }
        state.spawnNode(id, type);
        message = "Spawned " + type.idStem() + " (" + id + ")";
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

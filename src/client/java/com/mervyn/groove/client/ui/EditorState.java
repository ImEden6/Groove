package com.mervyn.groove.client.ui;

import groove.engine.Graph;
import groove.engine.NodeType;
import groove.engine.NodeParam;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client only editor state for the sequencer node graph. It holds the working copy of
 * a {@link Graph} plus everything the spec needs that isn't part of the graph model
 * itself: layout, selection, view transform, wire and value drags still in progress,
 * and undo and redo. See docs/SEQUENCER-UI-ARCHITECTURE.md, sections 1 through 3.
 *
 * Two things live outside this class on purpose. Sending an edited graph anywhere is
 * the screen's revision-aware submission bridge. Playback and transport state belongs to
 * the server as authoritative session state, not to editor state; {@link #togglePlay()}
 * just calls a hook.
 */
public final class EditorState {
    private static final int MAX_HISTORY = 100;
    private static final double MIN_ZOOM = 0.5, MAX_ZOOM = 2.0;
    private static final long COMPILE_DEBOUNCE_MILLIS = 50;

    public record WireDrag(String fromNode, String fromPort, Vec2 pointer) {}
    public record ValueDrag(String nodeId, String param, double startValue, double startPointerY, boolean fine) {}
    private record Snapshot(Graph graph, Map<String, Vec2> layout) {}

    private int version;
    private final Map<String, Graph.Node> nodes = new LinkedHashMap<>();
    private final LinkedHashSet<Graph.Edge> edges = new LinkedHashSet<>();
    private final EditorLayout layout = new EditorLayout();
    private final Set<String> selection = new LinkedHashSet<>();

    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();

    private double panX, panY, zoom = 1;
    private WireDrag wireDrag;
    private ValueDrag valueDrag;
    private Vec2 quickSpawnAt;
    private long pendingCompileAtMillis = -1;

    private Runnable onGraphChanged = () -> {};
    private Runnable onTogglePlay = () -> {};

    public EditorState(Graph initial) { loadGraph(initial); }

    // === graph in and out ===
    public void loadGraph(Graph graph) {
        if (graph.version() < 1 || graph.version() > Graph.CURRENT_VERSION)
            throw new IllegalArgumentException("Unsupported graph version");
        nodes.clear(); edges.clear(); selection.clear();
        undoStack.clear(); redoStack.clear();
        version = Graph.CURRENT_VERSION;
        for (Graph.Node node : graph.nodes()) nodes.put(node.id(), node);
        edges.addAll(graph.edges());
        layout.ensureDefaults(graph.nodes());
        pendingCompileAtMillis = -1;
    }

    /** Edited graphs use v3; incomplete working copies are validated at submission. */
    public Graph toGraph() {
        return new Graph(version, List.copyOf(nodes.values()), List.copyOf(edges));
    }
    public void dropSample(groove.engine.samples.AssetRef ref, Vec2 position, String target) {
        Graph.Node old = nodes.get(target);
        if (old != null && old.type() != NodeType.GENERATOR_SAMPLE && old.type() != NodeType.TONE) return;
        if (old == null && nodes.size() >= 64) throw new IllegalArgumentException("Maximum 64 nodes");
        pushUndo();
        String id = old == null ? uniqueId("sample") : old.id();
        Map<String, Double> params = old != null && old.type() == NodeType.GENERATOR_SAMPLE ? old.params()
                : Map.of(NodeParam.PITCH_RATIO, 1.0, NodeParam.GAIN, .8, NodeParam.PAN, 0.0);
        nodes.put(id, new Graph.Node(id, NodeType.GENERATOR_SAMPLE, params, ref));
        if (old == null) layout.place(id, position);
        select(id, false); markDirty();
    }

    public EditorLayout layout() { return layout; }
    public Collection<Graph.Node> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public Set<Graph.Edge> edges() { return Collections.unmodifiableSet(edges); }
    public Graph.Node node(String id) { return nodes.get(id); }

    public void onGraphChanged(Runnable callback) { this.onGraphChanged = callback == null ? () -> {} : callback; }
    public void onTogglePlay(Runnable callback) { this.onTogglePlay = callback == null ? () -> {} : callback; }
    /** Space. Actual play and stop state is server authoritative session state, not editor state. */
    public void togglePlay() { onTogglePlay.run(); }

    // === view transform ===
    public double zoom() { return zoom; }
    public Vec2 toWorld(double screenX, double screenY) { return new Vec2(screenX / zoom - panX, screenY / zoom - panY); }
    public Vec2 toScreen(Vec2 world) { return new Vec2((world.x() + panX) * zoom, (world.y() + panY) * zoom); }
    public void panByScreen(double dx, double dy) { panX += dx / zoom; panY += dy / zoom; }

    /** Wheel zoom, clamped between 0.5x and 2.0x per the spec, anchored to the cursor. */
    public void zoomAtScreenPoint(double factor, double screenX, double screenY) {
        Vec2 before = toWorld(screenX, screenY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        Vec2 after = toWorld(screenX, screenY);
        panX += after.x() - before.x();
        panY += after.y() - before.y();
    }

    /** F, double tapped, recoordinates the viewport to center all active nodes. */
    public void frameAll(double viewportWidth, double viewportHeight) {
        if (nodes.isEmpty()) { panX = 0; panY = 0; zoom = 1; return; }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (String id : nodes.keySet()) {
            Vec2 pos = layout.get(id);
            if (pos == null) continue;
            minX = Math.min(minX, pos.x()); minY = Math.min(minY, pos.y());
            maxX = Math.max(maxX, pos.x() + NodeGeometry.WIDTH); maxY = Math.max(maxY, pos.y() + NodeGeometry.HEIGHT);
        }
        double margin = 60;
        double spanX = Math.max(1, maxX - minX + margin * 2), spanY = Math.max(1, maxY - minY + margin * 2);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.min(viewportWidth / spanX, viewportHeight / spanY)));
        panX = -(minX - margin);
        panY = -(minY - margin);
    }

    // === selection ===
    public Set<String> selection() { return Collections.unmodifiableSet(selection); }
    public void select(String nodeId, boolean additive) {
        if (!additive) selection.clear();
        if (nodeId != null) selection.add(nodeId);
    }
    public void clearSelection() { selection.clear(); }

    // === node dragging ===
    /** Call once at the start of a node drag gesture, then moveSelectedBy per mouse move. */
    public void beginNodeDrag() { if (!selection.isEmpty()) pushUndo(); }
    public void moveSelectedBy(double dx, double dy) {
        for (String id : selection) layout.moveBy(id, dx, dy);
        markDirty();
    }

    // === delete and clone ===
    /** Delete or Backspace deletes selected nodes and their attached connection edges. */
    public void deleteSelected() {
        if (selection.isEmpty()) return;
        pushUndo();
        for (String id : selection) { nodes.remove(id); layout.remove(id); }
        edges.removeIf(e -> selection.contains(e.fromNode()) || selection.contains(e.toNode()));
        selection.clear();
        markDirty();
    }

    /** Net effect of Ctrl+C and Ctrl+V for a single client. Clones the selected node
     *  structure with a +20px spatial offset, including edges internal to the selection. */
    public void cloneSelected() {
        if (selection.isEmpty()) return;
        pushUndo();
        Map<String, String> renamed = new LinkedHashMap<>();
        for (String id : selection) renamed.put(id, uniqueId(id));
        for (var entry : renamed.entrySet()) {
            Graph.Node original = nodes.get(entry.getKey());
            nodes.put(entry.getValue(), new Graph.Node(entry.getValue(), original.type(), original.params(), original.sample()));
            Vec2 pos = layout.get(entry.getKey());
            layout.place(entry.getValue(), (pos == null ? new Vec2(60, 60) : pos).plus(20, 20));
        }
        List<Graph.Edge> internal = new java.util.ArrayList<>();
        for (Graph.Edge edge : edges)
            if (renamed.containsKey(edge.fromNode()) && renamed.containsKey(edge.toNode()))
                internal.add(new Graph.Edge(renamed.get(edge.fromNode()), edge.fromPort(), renamed.get(edge.toNode()), edge.toPort()));
        edges.addAll(internal);
        selection.clear();
        selection.addAll(renamed.values());
        markDirty();
    }
    private String uniqueId(String base) {
        String stem = base.replaceAll("_copy\\d*$", "");
        for (int n = 1; ; n++) {
            String candidate = stem + "_copy" + n;
            if (!nodes.containsKey(candidate)) return candidate;
        }
    }

    // === ports and cables ===
    /** Mirrors GraphCompiler's arity and cycle rules closely enough for immediate UI
     *  feedback, like port dimming and magnetism. Real validation still happens through
     *  GraphJson and GraphCompiler at the PatchSubmission boundary; this never replaces that. */
    public boolean canConnect(String fromId, String toId) {
        return canConnect(fromId, "out", toId, "in");
    }
    public boolean canConnect(String fromId, String fromPort, String toId, String toPort) {
        if (!nodes.containsKey(fromId) || !nodes.containsKey(toId)) return false;
        Graph.Node to = nodes.get(toId);
        var target = to.type().inputPort(toPort);
        if (target == null || !target.accepts(nodes.get(fromId).type().outputPort(fromPort))) return false;
        // OUTPUT chooses either pattern or audio; its two sockets share one input slot.
        long currentInputs = edges.stream().filter(e -> e.toNode().equals(toId)
                && (to.type() == NodeType.OUTPUT || e.toPort().equals(toPort))).count();
        boolean roomForMore = currentInputs < target.maxConnections()
                && target.accepts(nodes.get(fromId).type().outputPort(fromPort));
        return roomForMore
                && !edges.contains(new Graph.Edge(fromId, fromPort, toId, toPort))
                && (to.type() == NodeType.DELAY || !reaches(toId, fromId));
    }
    private boolean reaches(String fromId, String targetId) {
        Deque<String> stack = new ArrayDeque<>(List.of(fromId));
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (current.equals(targetId)) return true;
            if (!seen.add(current)) continue;
            for (Graph.Edge edge : edges) if (edge.fromNode().equals(current)
                    && nodes.get(edge.toNode()).type() != NodeType.DELAY) stack.push(edge.toNode());
        }
        return false;
    }
    public boolean connect(String fromId, String toId) {
        return connect(fromId, "out", toId, "in");
    }
    public boolean connect(String fromId, String fromPort, String toId, String toPort) {
        if (!canConnect(fromId, fromPort, toId, toPort)) return false;
        pushUndo();
        edges.add(new Graph.Edge(fromId, fromPort, toId, toPort));
        markDirty();
        return true;
    }
    /** Right clicking a port snips all wires touching that specific side of the node. */
    public void disconnectPort(String nodeId, boolean outputSide) {
        boolean any = edges.stream().anyMatch(e -> outputSide ? e.fromNode().equals(nodeId) : e.toNode().equals(nodeId));
        if (!any) return;
        pushUndo();
        edges.removeIf(e -> outputSide ? e.fromNode().equals(nodeId) : e.toNode().equals(nodeId));
        markDirty();
    }
    public void disconnectPort(String nodeId, String port, boolean outputSide) {
        var matching = edges.stream().filter(e -> outputSide ? e.fromNode().equals(nodeId) && e.fromPort().equals(port)
                : e.toNode().equals(nodeId) && e.toPort().equals(port)).toList();
        if (matching.isEmpty()) return;
        pushUndo(); edges.removeAll(matching); markDirty();
    }
    /** Inspector's "Disconnect All". */
    public void disconnectAll(String nodeId) {
        boolean any = edges.stream().anyMatch(e -> e.fromNode().equals(nodeId) || e.toNode().equals(nodeId));
        if (!any) return;
        pushUndo();
        edges.removeIf(e -> e.fromNode().equals(nodeId) || e.toNode().equals(nodeId));
        markDirty();
    }

    public boolean isWireDragging() { return wireDrag != null; }
    public WireDrag wireDrag() { return wireDrag; }
    public void startWireDrag(String fromNodeId, Vec2 pointerWorld) {
        startWireDrag(fromNodeId, "out", pointerWorld);
    }
    public void startWireDrag(String fromNodeId, String port, Vec2 pointerWorld) {
        if (nodes.containsKey(fromNodeId) && nodes.get(fromNodeId).type().outputPort(port) != null)
            wireDrag = new WireDrag(fromNodeId, port, pointerWorld);
    }
    public void updateWireDrag(Vec2 pointerWorld) {
        if (wireDrag != null) wireDrag = new WireDrag(wireDrag.fromNode(), wireDrag.fromPort(), pointerWorld);
    }
    public void cancelWireDrag() { wireDrag = null; }
    public boolean completeWireDrag(String toNodeId) {
        return completeWireDrag(toNodeId, "in");
    }
    public boolean completeWireDrag(String toNodeId, String port) {
        if (wireDrag == null) return false;
        boolean connected = connect(wireDrag.fromNode(), wireDrag.fromPort(), toNodeId, port);
        wireDrag = null;
        return connected;
    }

    // === value scrubbing ===
    /** Left click drag on a dial. Vertical delta maps to value via a per-param sensitivity
     *  (see {@link #sensitivity}), since a flat multiplier is either far too slow for a
     *  0..16000 range or far too twitchy for a 0..1 one. Ctrl applies an extra 0.1x for
     *  fine adjustment on top of that base sensitivity. */
    public void beginValueDrag(String nodeId, String param, double startPointerY, boolean fine) {
        Graph.Node node = nodes.get(nodeId);
        if (node == null) return;
        pushUndo();
        double startValue = node.params().getOrDefault(param, defaultParams(node.type()).getOrDefault(param, 0.0));
        valueDrag = new ValueDrag(nodeId, param, startValue, startPointerY, fine);
    }
    public void dragValueTo(double pointerY) {
        if (valueDrag == null) return;
        double scale = sensitivity(valueDrag.param()) * (valueDrag.fine() ? 0.1 : 1.0);
        double delta = (valueDrag.startPointerY() - pointerY) * scale;
        Graph.Node node = nodes.get(valueDrag.nodeId());
        if (node == null) return;
        Map<String, Double> params = new LinkedHashMap<>(node.params());
        double raw = valueDrag.startValue() + delta;
        params.put(valueDrag.param(), clampParam(node.type(), valueDrag.param(), raw, params));
        if (node.type() == NodeType.EUCLID && valueDrag.param().equals(NodeParam.STEPS)) {
            params.put(NodeParam.PULSES, Math.min(params.get(NodeParam.STEPS),
                    params.getOrDefault(NodeParam.PULSES, defaultParams(NodeType.EUCLID).get(NodeParam.PULSES))));
        }
        nodes.put(node.id(), new Graph.Node(node.id(), node.type(), params, node.sample(), node.birthNanos()));
        markDirty();
    }
    public void endValueDrag() { valueDrag = null; }
    public boolean isValueDragging() { return valueDrag != null; }

    /** Units per pixel of vertical drag. Seeds favor auditioning adjacent integer
     *  variations; continuous parameters favor a comfortable range sweep. */
    private static double sensitivity(String param) {
        return switch (param) {
            case NodeParam.FREQUENCY, NodeParam.CUTOFF_HZ -> 40.0;
            case NodeParam.GAIN -> 0.005;
            case NodeParam.PAN -> 0.01;
            case NodeParam.PITCH_RATIO -> 0.02;
            case NodeParam.WAVE -> 0.05;
            case NodeParam.CHANCE -> .01;
            case NodeParam.SEED -> 1.0;
            case NodeParam.STEPS_PER_CYCLE -> .3;
            case NodeParam.FACTOR -> 0.1;
            case NodeParam.STEPS, NodeParam.PULSES -> 0.3;
            case NodeParam.ROTATION -> 0.5;
            case NodeParam.RATE -> .05;
            case NodeParam.SYNC, NodeParam.MODE -> .05;
            case NodeParam.ATTACK, NodeParam.DECAY, NodeParam.RELEASE, NodeParam.SUSTAIN, NodeParam.GATE -> .005;
            case "value0", "value1", "value2", "value3", "value4", "value5", "value6", "value7" -> .01;
            case NodeParam.OFFSET -> 40;
            case NodeParam.FRAMES -> 120;
            default -> 1.0;
        };
    }

    public static double clampParam(NodeType type, String param, double value, Map<String, Double> existingParams) {
        if (type.isSignalNode()) return switch (param) {
            case NodeParam.RATE -> Math.max(type == NodeType.STEP_SEQUENCE ? .125 : .001, Math.min(type == NodeType.STEP_SEQUENCE ? 16 : 40, value));
            case NodeParam.SYNC, NodeParam.MODE -> Math.max(0, Math.min(1, Math.rint(value)));
            case NodeParam.WAVE -> Math.max(0, Math.min(3, Math.rint(value)));
            case NodeParam.STEPS -> Math.max(1, Math.min(8, Math.rint(value)));
            case NodeParam.FRAMES -> Math.max(64, Math.min(48000, Math.rint(value)));
            case NodeParam.ATTACK, NodeParam.DECAY, NodeParam.RELEASE -> Math.max(0, Math.min(8,value));
            case NodeParam.GATE -> Math.max(.001, Math.min(1,value));
            case NodeParam.SUSTAIN, NodeParam.GAIN -> Math.max(0, Math.min(1,value));
            case NodeParam.SCALE, NodeParam.OFFSET -> Math.max(-20000,Math.min(20000,value));
            case NodeParam.CUTOFF_HZ -> Math.max(20,Math.min(20000,value));
            case NodeParam.RESONANCE_Q -> Math.max(.1,Math.min(20,value));
            default -> Math.max(-1,Math.min(1,value));
        };
        return switch (param) {
            case NodeParam.CHANCE -> Math.max(0, Math.min(1, value));
            case NodeParam.SEED -> Math.max(0, Math.min(65535, Math.rint(value)));
            case NodeParam.STEPS_PER_CYCLE -> Math.max(1, Math.min(64, Math.rint(value)));
            case NodeParam.FREQUENCY -> Math.max(20.0, Math.min(16000.0, value));
            case NodeParam.CUTOFF_HZ -> Math.max(20.0, Math.min(20000.0, value));
            case NodeParam.GAIN -> Math.max(0.0, Math.min(1.0, value));
            case NodeParam.PAN -> Math.max(-1.0, Math.min(1.0, value));
            case NodeParam.PITCH_RATIO -> Math.max(0.25, Math.min(4.0, value));
            case NodeParam.WAVE -> Math.max(0.0, Math.min(1.0, Math.round(value)));
            case NodeParam.FACTOR -> Math.max(.25, Math.min(16.0, Math.round(value * 100.0) / 100.0));
            case NodeParam.STEPS -> Math.max(1.0, Math.min(64.0, Math.round(value)));
            case NodeParam.PULSES -> {
                double maxSteps = existingParams != null ? existingParams.getOrDefault(NodeParam.STEPS, 64.0) : 64.0;
                yield Math.max(0.0, Math.min(maxSteps, Math.round(value)));
            }
            case NodeParam.ROTATION -> Math.max(-64.0, Math.min(64.0, Math.round(value)));
            default -> value;
        };
    }

    public static Map<String, Double> defaultParams(NodeType type) {
        return switch (type) {
            case PROBABILITY -> Map.of(NodeParam.CHANCE, .5, NodeParam.SEED, 0.0);
            case POLYMETER -> Map.of(NodeParam.STEPS_PER_CYCLE, 4.0);
            case LFO -> Map.of(NodeParam.RATE,1.0,NodeParam.SYNC,0.0,NodeParam.WAVE,0.0);
            case ENVELOPE -> Map.of(NodeParam.ATTACK,.01,NodeParam.DECAY,.1,NodeParam.SUSTAIN,.5,NodeParam.RELEASE,.1,NodeParam.MODE,0.0);
            case ATTENUVERTER -> Map.of(NodeParam.SCALE,1.0,NodeParam.OFFSET,0.0);
            case STEP_SEQUENCE -> Map.ofEntries(Map.entry(NodeParam.STEPS,4.0),Map.entry(NodeParam.RATE,1.0),Map.entry(NodeParam.GATE,.5),
                    Map.entry("value0",1.0),Map.entry("value1",0.0),Map.entry("value2",.5),Map.entry("value3",0.0),
                    Map.entry("value4",0.0),Map.entry("value5",0.0),Map.entry("value6",0.0),Map.entry("value7",0.0));
            case FILTER -> Map.of(NodeParam.CUTOFF_HZ,20000.0,NodeParam.RESONANCE_Q,groove.engine.Biquad.DEFAULT_Q);
            case DELAY -> Map.of(NodeParam.FRAMES,64.0);
            case MIX_BUS -> Map.of(NodeParam.GAIN,1.0);
            case GENERATOR_SAMPLE -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put(NodeParam.PITCH_RATIO, 1.0); m.put(NodeParam.GAIN, .8); m.put(NodeParam.PAN, 0.0);
                yield m;
            }
            case TONE -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put(NodeParam.FREQUENCY, 220.0); m.put(NodeParam.GAIN, 0.25); m.put(NodeParam.PAN, 0.0); m.put(NodeParam.WAVE, 0.0); m.put(NodeParam.CUTOFF_HZ, 20000.0);
                yield m;
            }
            case EUCLID -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put(NodeParam.STEPS, 16.0); m.put(NodeParam.PULSES, 4.0); m.put(NodeParam.ROTATION, 0.0);
                yield m;
            }
            case FAST -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put(NodeParam.FACTOR, 2.0);
                yield m;
            }
            default -> Map.of();
        };
    }
    /** Every applicable param for a node's type, showing the type's default for a key the
     *  node hasn't set yet (e.g. cutoffHz on a tone node saved before that param existed)
     *  overlaid with the node's actual values, so the Inspector shows newly added
     *  parameters on older graphs instead of never displaying them. */
    public static Map<String, Double> displayParams(Graph.Node node) {
        Map<String, Double> merged = new LinkedHashMap<>(defaultParams(node.type()));
        merged.putAll(node.params());
        return merged;
    }

    // === quick spawn palette ===
    public boolean isQuickSpawnOpen() { return quickSpawnAt != null; }
    public Vec2 quickSpawnAt() { return quickSpawnAt; }
    /** Shift+A or Tab opens the fuzzy search quick spawn palette at the given world point. */
    public void openQuickSpawn(Vec2 atWorld) { quickSpawnAt = atWorld; }
    public void closeQuickSpawn() { quickSpawnAt = null; }
    public void spawnNode(String id, NodeType type) {
        if (quickSpawnAt == null || nodes.containsKey(id)) return;
        pushUndo();
        nodes.put(id, new Graph.Node(id, type, defaultParams(type)));
        layout.place(id, quickSpawnAt);
        quickSpawnAt = null;
        markDirty();
    }

    // === undo and redo ===
    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(new Snapshot(toGraph(), layout.snapshot()));
        applySnapshot(undoStack.pop());
    }
    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(new Snapshot(toGraph(), layout.snapshot()));
        applySnapshot(redoStack.pop());
    }
    private void applySnapshot(Snapshot snapshot) {
        nodes.clear();
        for (Graph.Node node : snapshot.graph().nodes()) nodes.put(node.id(), node);
        edges.clear();
        edges.addAll(snapshot.graph().edges());
        version = snapshot.graph().version();
        layout.restore(snapshot.layout());
        selection.retainAll(nodes.keySet());
        markDirty();
    }
    private void pushUndo() {
        undoStack.push(new Snapshot(toGraph(), layout.snapshot()));
        if (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
        redoStack.clear();
    }

    // === compile debounce ===
    /** Debounces AST engine compilation by 50ms, per the spec. Call tick() once per
     *  client tick; onGraphChanged fires at most once per burst of edits, 50ms after the last one. */
    private void markDirty() { pendingCompileAtMillis = System.currentTimeMillis() + COMPILE_DEBOUNCE_MILLIS; }
    public void tick() {
        if (pendingCompileAtMillis >= 0 && System.currentTimeMillis() >= pendingCompileAtMillis) {
            pendingCompileAtMillis = -1;
            onGraphChanged.run();
        }
    }
}

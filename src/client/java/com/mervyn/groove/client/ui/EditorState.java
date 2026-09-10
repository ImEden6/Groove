package com.mervyn.groove.client.ui;

import groove.engine.Graph;

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

    public record WireDrag(String fromNode, Vec2 pointer) {}
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
        nodes.clear(); edges.clear(); selection.clear();
        undoStack.clear(); redoStack.clear();
        version = graph.version();
        for (Graph.Node node : graph.nodes()) nodes.put(node.id(), node);
        edges.addAll(graph.edges());
        layout.ensureDefaults(graph.nodes());
        pendingCompileAtMillis = -1;
    }

    /** Bumps the declared version to 2 if a sample node is present, since that's the
     *  one version rule GraphCompiler enforces that's easy to just satisfy here instead
     *  of surprising the user with a rejection at submit time. Everything else is left
     *  to real validation at the PatchSubmission boundary. */
    public Graph toGraph() {
        int effectiveVersion = nodes.values().stream().anyMatch(n -> n.type().equals("generator/sample"))
                ? Math.max(version, 2) : version;
        return new Graph(effectiveVersion, List.copyOf(nodes.values()), List.copyOf(edges));
    }
    public void dropSample(groove.engine.samples.AssetRef ref, Vec2 position, String target) {
        Graph.Node old = nodes.get(target);
        if (old != null && !old.type().equals("generator/sample") && !old.type().equals("tone")) return;
        if (old == null && nodes.size() >= 64) throw new IllegalArgumentException("Maximum 64 nodes");
        pushUndo();
        String id = old == null ? uniqueId("sample") : old.id();
        Map<String, Double> params = old != null && old.type().equals("generator/sample") ? old.params()
                : Map.of("pitchRatio", 1.0, "gain", .8, "pan", 0.0);
        nodes.put(id, new Graph.Node(id, "generator/sample", params, ref));
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
    public boolean hasOutputPort(String nodeId) {
        Graph.Node node = nodes.get(nodeId);
        return node != null && !node.type().equals("output");
    }
    public boolean hasInputPort(String nodeId) {
        Graph.Node node = nodes.get(nodeId);
        return node != null && !node.type().equals("tone") && !node.type().equals("generator/sample");
    }

    /** Mirrors GraphCompiler's arity and cycle rules closely enough for immediate UI
     *  feedback, like port dimming and magnetism. Real validation still happens through
     *  GraphJson and GraphCompiler at the PatchSubmission boundary; this never replaces that. */
    public boolean canConnect(String fromId, String toId) {
        if (fromId.equals(toId) || !hasOutputPort(fromId) || !hasInputPort(toId)) return false;
        Graph.Node to = nodes.get(toId);
        long currentInputs = edges.stream().filter(e -> e.toNode().equals(toId)).count();
        boolean roomForMore = to.type().equals("stack") ? currentInputs < 16 : currentInputs < 1;
        return roomForMore
                && !edges.contains(new Graph.Edge(fromId, "out", toId, "in"))
                && !reaches(toId, fromId);
    }
    private boolean reaches(String fromId, String targetId) {
        Deque<String> stack = new ArrayDeque<>(List.of(fromId));
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (current.equals(targetId)) return true;
            if (!seen.add(current)) continue;
            for (Graph.Edge edge : edges) if (edge.fromNode().equals(current)) stack.push(edge.toNode());
        }
        return false;
    }
    public boolean connect(String fromId, String toId) {
        if (!canConnect(fromId, toId)) return false;
        pushUndo();
        edges.add(new Graph.Edge(fromId, "out", toId, "in"));
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
        if (hasOutputPort(fromNodeId)) wireDrag = new WireDrag(fromNodeId, pointerWorld);
    }
    public void updateWireDrag(Vec2 pointerWorld) {
        if (wireDrag != null) wireDrag = new WireDrag(wireDrag.fromNode(), pointerWorld);
    }
    public void cancelWireDrag() { wireDrag = null; }
    public boolean completeWireDrag(String toNodeId) {
        if (wireDrag == null) return false;
        boolean connected = connect(wireDrag.fromNode(), toNodeId);
        wireDrag = null;
        return connected;
    }

    // === value scrubbing ===
    /** Left click drag on a dial. Vertical delta maps to value; Ctrl gives 0.01 fine steps. */
    public void beginValueDrag(String nodeId, String param, double startPointerY, boolean fine) {
        Graph.Node node = nodes.get(nodeId);
        if (node == null) return;
        pushUndo();
        double startValue = node.params().getOrDefault(param, defaultParams(node.type()).getOrDefault(param, 0.0));
        valueDrag = new ValueDrag(nodeId, param, startValue, startPointerY, fine);
    }
    public void dragValueTo(double pointerY) {
        if (valueDrag == null) return;
        double delta = (valueDrag.startPointerY() - pointerY) * (valueDrag.fine() ? 0.01 : 1.0);
        Graph.Node node = nodes.get(valueDrag.nodeId());
        if (node == null) return;
        Map<String, Double> params = new LinkedHashMap<>(node.params());
        double raw = valueDrag.startValue() + delta;
        params.put(valueDrag.param(), clampParam(node.type(), valueDrag.param(), raw, params));
        nodes.put(node.id(), new Graph.Node(node.id(), node.type(), params, node.sample()));
        markDirty();
    }
    public void endValueDrag() { valueDrag = null; }
    public boolean isValueDragging() { return valueDrag != null; }

    public static double clampParam(String type, String param, double value, Map<String, Double> existingParams) {
        return switch (param) {
            case "frequency" -> Math.max(20.0, Math.min(16000.0, value));
            case "cutoffHz" -> Math.max(20.0, Math.min(20000.0, value));
            case "gain" -> Math.max(0.0, Math.min(1.0, value));
            case "pan" -> Math.max(-1.0, Math.min(1.0, value));
            case "pitchRatio" -> Math.max(0.1, Math.min(8.0, value));
            case "wave" -> Math.max(0.0, Math.min(1.0, Math.round(value)));
            case "factor" -> Math.max(1.0, Math.min(16.0, Math.round(value)));
            case "steps" -> Math.max(1.0, Math.min(64.0, Math.round(value)));
            case "pulses" -> {
                double maxSteps = existingParams != null ? existingParams.getOrDefault("steps", 64.0) : 64.0;
                yield Math.max(0.0, Math.min(maxSteps, Math.round(value)));
            }
            case "rotation" -> Math.max(-64.0, Math.min(64.0, Math.round(value)));
            default -> value;
        };
    }

    public static Map<String, Double> defaultParams(String type) {
        return switch (type) {
            case "tone" -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put("frequency", 220.0); m.put("gain", 0.25); m.put("pan", 0.0); m.put("wave", 0.0); m.put("cutoffHz", 20000.0);
                yield m;
            }
            case "euclid" -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put("steps", 16.0); m.put("pulses", 4.0); m.put("rotation", 0.0);
                yield m;
            }
            case "fast" -> {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put("factor", 2.0);
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
    public void spawnNode(String id, String type) {
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

package com.mervyn.groove.client.ui;

import groove.engine.Graph;

import java.util.Optional;

/**
 * Translates raw pointer and keyboard events into {@link EditorState} mutations. It
 * deliberately has no Minecraft dependency such as GLFW key codes, GuiGraphics, or
 * Screen. The eventual GrooveEditorScreen maps real input events onto this API. This
 * is the one interaction codepath shared by all 4 themes, see
 * docs/SEQUENCER-UI-ARCHITECTURE.md section 3. Only ThemeRenderer differs per theme,
 * never this class.
 */
public final class InputController {
    public enum Key { SPACE, DELETE, BACKSPACE, TAB, F, Z, Y, C, A }
    public enum Button { PRIMARY, SECONDARY, MIDDLE }
    public enum EscapeConsumer { TEXT_FIELD, AUDITION, QUICK_SPAWN, WIRE_DRAG, SELECTION, PASSTHROUGH }

    private static final long DOUBLE_TAP_MILLIS = 350;

    private final EditorState state;
    private double viewportWidth = 1, viewportHeight = 1;

    private boolean panning, draggingNodes, spaceHeld;
    private double lastScreenX, lastScreenY;
    private PortHit hoveredInputPort;
    private long lastFPressMillis = -1;

    public InputController(EditorState state) { this.state = state; }

    public void setViewport(double width, double height) { viewportWidth = width; viewportHeight = height; }
    /** Tracks whether Space is held, purely for the Space plus left click drag canvas pan
     *  alternative. Space does not toggle play -- that's Play/Stop only now, since Space was
     *  also the pan modifier and there's no clean tap-vs-hold-to-pan split worth the complexity. */
    public void spaceKeyChanged(boolean held) { spaceHeld = held; }
    public void mouseMoved(double screenX, double screenY) { lastScreenX = screenX; lastScreenY = screenY; }

    // === keyboard ===
    /** Global history, node manipulation, and navigation bindings from the UI spec
     *  section 2, minus Escape. See handleEscape, which needs extra context this method doesn't have. */
    public boolean keyDown(Key key, boolean ctrl, boolean shift, boolean textFieldActive) {
        if (textFieldActive) return false; // the field owns its own key handling
        return switch (key) {
            // Space is handled on release, by spaceKeyChanged, so a Space-held pan drag
            // doesn't also toggle play -- see spaceKeyChanged's doc comment.
            case SPACE -> true;
            case Z -> ctrl && run(state::undo);
            case Y -> ctrl && run(state::redo);
            case DELETE, BACKSPACE -> run(state::deleteSelected);
            case C -> ctrl && run(state::cloneSelected); // net effect of the spec's Ctrl+C and Ctrl+V for a single client
            case A -> shift && run(() -> state.openQuickSpawn(state.toWorld(lastScreenX, lastScreenY)));
            case TAB -> run(() -> state.openQuickSpawn(state.toWorld(lastScreenX, lastScreenY)));
            case F -> { handleFrameDoubleTap(); yield true; }
        };
    }
    private boolean run(Runnable action) { action.run(); return true; }

    private void handleFrameDoubleTap() {
        long now = System.currentTimeMillis();
        if (lastFPressMillis >= 0 && now - lastFPressMillis <= DOUBLE_TAP_MILLIS) {
            state.frameAll(viewportWidth, viewportHeight);
            lastFPressMillis = -1;
        } else {
            lastFPressMillis = now;
        }
    }

    /** Event priority ladder from the UI spec section 2. Steps 1 and 2 belong to widgets
     *  this controller does not own, a focused text field and an auditioning sample
     *  preview. The caller reports their state so the ladder still evaluates in the right order. */
    public EscapeConsumer handleEscape(boolean textFieldActive, boolean auditioning) {
        if (textFieldActive) return EscapeConsumer.TEXT_FIELD;
        if (auditioning) return EscapeConsumer.AUDITION;
        if (state.isQuickSpawnOpen()) { state.closeQuickSpawn(); return EscapeConsumer.QUICK_SPAWN; }
        if (state.isWireDragging()) { state.cancelWireDrag(); return EscapeConsumer.WIRE_DRAG; }
        if (!state.selection().isEmpty()) { state.clearSelection(); return EscapeConsumer.SELECTION; }
        return EscapeConsumer.PASSTHROUGH;
    }

    // === mouse ===
    public void mouseDown(Button button, double screenX, double screenY, boolean ctrl) {
        lastScreenX = screenX; lastScreenY = screenY;
        Vec2 world = state.toWorld(screenX, screenY);

        if (button == Button.MIDDLE || (button == Button.PRIMARY && spaceHeld && hitNode(world).isEmpty())) {
            panning = true;
            return;
        }
        if (button == Button.SECONDARY) {
            hitPort(world).ifPresent(hit -> state.disconnectPort(hit.nodeId(), hit.port(), hit.output()));
            return;
        }
        // The primary button starts a wire from an output port, selects and drags a node body, or deselects on empty canvas.
        Optional<PortHit> port = hitPort(world);
        if (port.isPresent() && port.get().output()) {
            state.startWireDrag(port.get().nodeId(), port.get().port(), world);
            return;
        }
        Optional<String> nodeId = hitNode(world);
        if (nodeId.isPresent()) {
            state.select(nodeId.get(), ctrl);
            state.beginNodeDrag();
            draggingNodes = true;
            return;
        }
        state.clearSelection();
    }

    /** {@code dx}/{@code dy} must be the delta Minecraft itself hands {@code Screen.mouseDragged}
     *  (its dragX/dragY parameters), not recomputed from the last known pointer position here:
     *  Minecraft's MouseHandler calls mouseMoved immediately before mouseDragged on every drag
     *  tick, and mouseMoved already advances lastScreenX/lastScreenY to the current position, so
     *  a self-computed delta against those fields is always ~0. {@code canvasVisible} has no
     *  default: a caller must say what "visible" means for its own overlays, since silently
     *  treating everything as visible would let a wire connect through a hidden panel. */
    public void mouseDrag(double screenX, double screenY, double dx, double dy,
                          java.util.function.BiPredicate<Double, Double> canvasVisible) {
        lastScreenX = screenX; lastScreenY = screenY;
        if (panning) { state.panByScreen(dx, dy); return; }
        if (state.isWireDragging()) { state.updateWireDrag(state.toWorld(screenX, screenY)); trackHoveredPort(screenX, screenY, canvasVisible); return; }
        if (state.isValueDragging()) { state.dragValueTo(screenY); return; }
        if (draggingNodes) state.moveSelectedBy(dx / state.zoom(), dy / state.zoom());
    }

    /** Re-hit-test at release: the final pointer position may have no preceding drag event. */
    public void mouseUp(double screenX, double screenY, java.util.function.BiPredicate<Double, Double> canvasVisible) {
        if (state.isWireDragging()) trackHoveredPort(screenX, screenY, canvasVisible);
        mouseUp();
    }

    public void mouseUp() {
        if (state.isWireDragging()) {
            if (hoveredInputPort != null) state.completeWireDrag(hoveredInputPort.nodeId(), hoveredInputPort.port()); else state.cancelWireDrag();
        }
        if (state.isValueDragging()) state.endValueDrag();
        panning = false; draggingNodes = false; hoveredInputPort = null;
    }

    /** The mouse wheel zooms between 0.5x and 2.0x, anchored to cursor position. */
    public void mouseScroll(double amount, double screenX, double screenY) {
        state.zoomAtScreenPoint(Math.pow(1.1, amount), screenX, screenY);
    }

    /** Hook for a dial widget. It does its own hit testing; this just starts the drag. */
    public void startValueDrag(String nodeId, String param, double screenY, boolean fine) {
        state.beginValueDrag(nodeId, param, screenY, fine);
    }

    private void trackHoveredPort(double screenX, double screenY, java.util.function.BiPredicate<Double, Double> canvasVisible) {
        hoveredInputPort = null;
        if (!canvasVisible.test(screenX, screenY)) return;
        Vec2 world = state.toWorld(screenX, screenY);
        hoveredInputPort = hitPort(world).filter(hit -> !hit.output()).filter(hit -> {
            Vec2 port = state.toScreen(NodeGeometry.port(state.layout().get(hit.nodeId()), state.node(hit.nodeId()).type(), hit.port(), false));
            return canvasVisible.test(port.x(), port.y());
        }).orElse(null);
    }

    // === hit testing, shares NodeGeometry with the eventual ThemeRenderer ===
    private record PortHit(String nodeId, String port, boolean output) {}

    private Optional<String> hitNode(Vec2 world) {
        for (Graph.Node node : state.nodes()) {
            Vec2 origin = state.layout().get(node.id());
            if (origin != null && NodeGeometry.containsBody(origin, world)) return Optional.of(node.id());
        }
        return Optional.empty();
    }
    private Optional<PortHit> hitPort(Vec2 world) {
        for (Graph.Node node : state.nodes()) {
            Vec2 origin = state.layout().get(node.id());
            if (origin == null) continue;
            for (var port : node.type().outputPorts())
                if (NodeGeometry.port(origin,node.type(),port.name(),true).distanceTo(world) <= NodeGeometry.MAGNET_RADIUS)
                    return Optional.of(new PortHit(node.id(),port.name(),true));
            for (var port : node.type().inputPorts())
                if (NodeGeometry.port(origin,node.type(),port.name(),false).distanceTo(world) <= NodeGeometry.MAGNET_RADIUS)
                    return Optional.of(new PortHit(node.id(),port.name(),false));
        }
        return Optional.empty();
    }
}

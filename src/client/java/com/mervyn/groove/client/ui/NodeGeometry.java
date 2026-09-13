package com.mervyn.groove.client.ui;

/**
 * Node box layout shared by hit testing (here and in InputController) and drawing
 * (ThemeRenderer, once built), so input and rendering never disagree about where a
 * port actually is. All values are in world space units. InputController divides by
 * EditorState's zoom itself; these constants never bake zoom in.
 */
public final class NodeGeometry {
    private NodeGeometry() {}

    public static final double WIDTH = 148, HEADER_HEIGHT = 22, BODY_HEIGHT = 40;
    public static final double HEIGHT = HEADER_HEIGHT + BODY_HEIGHT;
    /** Matches the UI spec's "magnetically pull the wire tip when within 12px". */
    public static final double MAGNET_RADIUS = 12;

    public static Vec2 outputPort(Vec2 nodeOrigin) { return nodeOrigin.plus(WIDTH, HEIGHT / 2); }
    public static Vec2 inputPort(Vec2 nodeOrigin) { return nodeOrigin.plus(0, HEIGHT / 2); }
    public static Vec2 port(Vec2 origin, groove.engine.NodeType type, String name, boolean output) {
        var ports = output ? type.outputPorts() : type.inputPorts();
        for (int i=0;i<ports.size();i++) if (ports.get(i).name().equals(name))
            return origin.plus(output ? WIDTH : 0, HEIGHT/2 + i*26);
        throw new IllegalArgumentException("Unknown port " + name);
    }

    public static boolean containsBody(Vec2 nodeOrigin, Vec2 point) {
        return point.x() >= nodeOrigin.x() && point.x() <= nodeOrigin.x() + WIDTH
                && point.y() >= nodeOrigin.y() && point.y() <= nodeOrigin.y() + HEIGHT;
    }
}

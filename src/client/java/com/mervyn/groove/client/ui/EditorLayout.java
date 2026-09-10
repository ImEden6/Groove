package com.mervyn.groove.client.ui;

import groove.engine.Graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Node canvas positions, deliberately kept outside Graph. See Graph's own doc comment. */
public final class EditorLayout {
    private final Map<String, Vec2> positions = new LinkedHashMap<>();

    public Vec2 get(String nodeId) { return positions.get(nodeId); }
    public void place(String nodeId, Vec2 position) { positions.put(nodeId, position); }
    public void moveBy(String nodeId, double dx, double dy) {
        positions.computeIfPresent(nodeId, (id, pos) -> pos.plus(dx, dy));
    }
    public void remove(String nodeId) { positions.remove(nodeId); }
    public Map<String, Vec2> snapshot() { return new LinkedHashMap<>(positions); }
    public void restore(Map<String, Vec2> snapshot) { positions.clear(); positions.putAll(snapshot); }

    /** Staggers any node from the list that doesn't have a position yet (e.g. freshly loaded patch). */
    public void ensureDefaults(List<Graph.Node> nodes) {
        int placed = positions.size();
        for (Graph.Node node : nodes) {
            if (positions.containsKey(node.id())) continue;
            int column = placed % 4, row = placed / 4;
            positions.put(node.id(), new Vec2(60 + column * (NodeGeometry.WIDTH + 40), 60 + row * (NodeGeometry.HEIGHT + 40)));
            placed++;
        }
    }
}

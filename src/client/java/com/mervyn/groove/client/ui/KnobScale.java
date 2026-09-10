package com.mervyn.groove.client.ui;

import groove.engine.Graph;

/** Uses the editor's actual clamps, including the current Euclidean step count. */
public final class KnobScale {
    private KnobScale() {}

    public static float angle(Graph.Node node, String param, double value) {
        double min = EditorState.clampParam(node.type(), param, -Double.MAX_VALUE, node.params());
        double max = EditorState.clampParam(node.type(), param, Double.MAX_VALUE, node.params());
        double fraction = max > min ? (value - min) / (max - min) : 0;
        if (!Double.isFinite(fraction)) fraction = 0;
        return (float) (-135 + 270 * Math.max(0, Math.min(1, fraction)));
    }
}

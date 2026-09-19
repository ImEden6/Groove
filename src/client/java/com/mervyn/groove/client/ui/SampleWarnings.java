package com.mervyn.groove.client.ui;

import groove.engine.Graph;
import groove.engine.samples.SampleCatalog;

/** Why a node's sample can't play locally, or null when it can. */
public final class SampleWarnings {
    private SampleWarnings() {}

    public static String of(Graph.Node node, SampleCatalog catalog) {
        if (node == null || node.sample() == null) return null;
        return switch (catalog.status(node.sample())) {
            case READY -> null;
            case MISSING -> "Sample missing";
            case HASH_MISMATCH -> "Sample doesn't match the server copy";
        };
    }
}

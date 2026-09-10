package com.mervyn.groove.client.ui;

import groove.engine.Graph;

/** A node resolved to its current screen position for one frame of drawing. */
public record NodeView(Graph.Node node, Vec2 screenOrigin) {}

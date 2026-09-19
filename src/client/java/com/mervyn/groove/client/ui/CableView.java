package com.mervyn.groove.client.ui;

/** A connection resolved to screen space for one frame of drawing. */
public record CableView(Vec2 fromScreen, Vec2 toScreen) {}

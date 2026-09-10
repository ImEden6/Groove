package com.mervyn.groove.client.ui;

/** A connection resolved to screen space for one frame of drawing. pulsePhase runs 0 to 1 across a beat. */
public record CableView(Vec2 fromScreen, Vec2 toScreen, float pulsePhase) {}

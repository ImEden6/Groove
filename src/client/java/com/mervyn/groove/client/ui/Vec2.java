package com.mervyn.groove.client.ui;

/** A 2D point or offset, in world space or screen space depending on context. */
public record Vec2(double x, double y) {
    public static final Vec2 ZERO = new Vec2(0, 0);

    public Vec2 plus(Vec2 other) { return new Vec2(x + other.x, y + other.y); }
    public Vec2 plus(double dx, double dy) { return new Vec2(x + dx, y + dy); }
    public Vec2 minus(Vec2 other) { return new Vec2(x - other.x, y - other.y); }
    public Vec2 scale(double factor) { return new Vec2(x * factor, y * factor); }
    public double distanceTo(Vec2 other) { return Math.hypot(x - other.x, y - other.y); }
}

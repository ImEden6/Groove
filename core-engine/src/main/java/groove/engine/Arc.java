package groove.engine;

/** A finite, half-open interval in musical cycles. */
public record Arc(double start, double end) {
    public Arc {
        if (!Double.isFinite(start) || !Double.isFinite(end) || end < start)
            throw new IllegalArgumentException("Expected finite start <= end");
    }

    public Arc intersect(Arc other) {
        double s = Math.max(start, other.start);
        double e = Math.min(end, other.end);
        return s < e ? new Arc(s, e) : null;
    }

    public Arc scale(double factor) {
        if (!Double.isFinite(factor) || factor <= 0)
            throw new IllegalArgumentException("Scale must be finite and positive");
        return new Arc(start * factor, end * factor);
    }
}

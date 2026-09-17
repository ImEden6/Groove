package groove.engine;

/** Fixed-tempo mapping. Audio advances by integer frames, never by game ticks. */
public record Transport(int sampleRate, double bpm, int beatsPerCycle) {
    public Transport {
        if (sampleRate < 8000 || sampleRate > 192000 || !Double.isFinite(bpm)
                || bpm < 1 || bpm > 1000 || beatsPerCycle < 1 || beatsPerCycle > 32)
            throw new IllegalArgumentException("Invalid transport");
    }

    public double secondsPerCycle() { return 60.0 * beatsPerCycle / bpm; }
    public double framesPerCycle() { return sampleRate * secondsPerCycle(); }
    public long frameAt(double cycle) {
        double frame = cycle * framesPerCycle();
        if (!Double.isFinite(frame) || Math.abs(frame) >= Long.MAX_VALUE)
            throw new IllegalArgumentException("Cycle outside frame range");
        return Math.round(frame);
    }
    public double cycleAt(long frame) { return frame / framesPerCycle(); }
}

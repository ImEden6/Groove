package groove.engine.samples;

public record SampleVoice(
        AssetRef asset,
        double pitchRatio,
        double gain,
        double pan,
        double cutoffHz,
        double resonanceQ,
        SampleRegion region,
        boolean loop,
        double loopStart,
        double loopEnd,
        double loopFadeMs
) {
    public static final double DEFAULT_LOOP_START = 0.25;
    public static final double DEFAULT_LOOP_END = 0.9;
    public static final double DEFAULT_LOOP_FADE_MS = 20.0;

    public SampleVoice(AssetRef asset, double pitchRatio, double gain, double pan, double cutoffHz, double resonanceQ, SampleRegion region) {
        this(asset, pitchRatio, gain, pan, cutoffHz, resonanceQ, region, false, DEFAULT_LOOP_START, DEFAULT_LOOP_END, DEFAULT_LOOP_FADE_MS);
    }
    public SampleVoice(AssetRef asset, double pitchRatio, double gain, double pan, double cutoffHz, double resonanceQ) {
        this(asset, pitchRatio, gain, pan, cutoffHz, resonanceQ, SampleRegion.ALL);
    }
    public SampleVoice(AssetRef asset, double pitchRatio, double gain, double pan) {
        this(asset, pitchRatio, gain, pan, 20000, groove.engine.Biquad.DEFAULT_Q);
    }
    public SampleVoice {
        if (!Double.isFinite(cutoffHz) || cutoffHz < 20 || cutoffHz > 20000
                || !Double.isFinite(resonanceQ) || resonanceQ < .1 || resonanceQ > 20
                || asset == null || region == null || !Double.isFinite(pitchRatio) || pitchRatio < .25 || pitchRatio > 4
                || !Double.isFinite(gain) || gain < 0 || gain > 1 || !Double.isFinite(pan) || Math.abs(pan) > 1)
            throw new IllegalArgumentException("Invalid sample voice settings");
        if (loop) {
            if (!Double.isFinite(loopStart) || loopStart < 0 || loopStart > 1
                    || !Double.isFinite(loopEnd) || loopEnd < 0 || loopEnd > 1
                    || loopStart >= loopEnd
                    || !Double.isFinite(loopFadeMs) || loopFadeMs < 0 || loopFadeMs > 500)
                throw new IllegalArgumentException("Invalid sample voice settings");
        } else {
            loopStart = DEFAULT_LOOP_START;
            loopEnd = DEFAULT_LOOP_END;
            loopFadeMs = DEFAULT_LOOP_FADE_MS;
        }
    }
    /** Select within the explicit source interval. Replaces a previous slice selection. */
    public SampleVoice slice(int slices, int index, boolean reverse) {
        return new SampleVoice(asset, pitchRatio, gain, pan, cutoffHz, resonanceQ,
                new SampleRegion(region.startFrame(), region.endFrame(), slices, index, reverse),
                loop, loopStart, loopEnd, loopFadeMs);
    }
    /** Single-voice preparation convenience; renderers prepare a bounded, deduplicated bank. */
    public SamplePlayback prepare(SampleData pcm) {
        return prepare(pcm, 48000);
    }
    public SamplePlayback prepare(SampleData pcm, int outputRate) {
        SampleData cropped = pcm.copyRegion(region.start(pcm), region.end(pcm), region.reverse());
        return new SamplePlayback(this, cropped, LoopGeometry.resolve(cropped, this, outputRate));
    }
    public double value(SampleData pcm, double age, int channel) {
        return value(pcm, age, channel, pcm.rate());
    }
    /** Legacy whole-asset convenience. For regions, prepare once on the control thread and use SamplePlayback.value. */
    public double value(SampleData pcm, double age, int channel, int outputRate) {
        if (!region.equals(SampleRegion.ALL)) throw new IllegalArgumentException("Prepare region voices before rendering");
        return SamplePlayback.value(this, pcm, age, channel, outputRate);
    }
}

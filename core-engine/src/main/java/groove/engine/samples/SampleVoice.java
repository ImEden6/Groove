package groove.engine.samples;

public record SampleVoice(AssetRef asset, double pitchRatio, double gain, double pan, double cutoffHz, double resonanceQ) {
    public SampleVoice(AssetRef asset, double pitchRatio, double gain, double pan) {
        this(asset, pitchRatio, gain, pan, 20000, groove.engine.Biquad.DEFAULT_Q);
    }
    public SampleVoice {
        if (!Double.isFinite(cutoffHz) || cutoffHz < 20 || cutoffHz > 20000
                || !Double.isFinite(resonanceQ) || resonanceQ < .1 || resonanceQ > 20
                || asset == null || !Double.isFinite(pitchRatio) || pitchRatio < .25 || pitchRatio > 4
                || !Double.isFinite(gain) || gain < 0 || gain > 1 || !Double.isFinite(pan) || Math.abs(pan) > 1)
            throw new IllegalArgumentException("Invalid sample voice settings");
    }
    public double value(SampleData pcm, double age, int channel) {
        return value(pcm, age, channel, pcm.rate());
    }
    public double value(SampleData pcm, double age, int channel, int outputRate) {
        double duration = pcm.duration() / pitchRatio;
        if (age < 0 || age >= duration) return 0;
        double envelope = Math.min(1, Math.min(age / .001, (duration - age) / .005));
        double balance = pcm.channels() == 1
                ? (channel == 0 ? Math.cos((pan + 1) * Math.PI / 4) : Math.sin((pan + 1) * Math.PI / 4))
                : (channel == 0 ? Math.min(1, 1 - pan) : Math.min(1, 1 + pan));
        return pcm.at(age * pcm.rate() * pitchRatio, channel, pcm.rate() * pitchRatio / outputRate) * gain * balance * envelope;
    }
}

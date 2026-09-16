package groove.engine.samples;

/** Immutable resolved voice. PCM is already cropped/reversed and independently prefiltered. */
public record SamplePlayback(SampleVoice voice, SampleData pcm) {
    public SamplePlayback {
        java.util.Objects.requireNonNull(voice); java.util.Objects.requireNonNull(pcm);
    }
    public double duration() { return pcm.duration() / voice.pitchRatio(); }
    public void validateOutputRate(int rate) {
        double step = pcm.rate() * voice.pitchRatio() / rate;
        if (rate <= 0 || step > 16) throw new IllegalArgumentException("Sample resampling step exceeds 16");
    }
    public double value(double age, int channel, int outputRate) {
        return value(voice, pcm, age, channel, outputRate);
    }
    static double value(SampleVoice voice, SampleData pcm, double age, int channel, int outputRate) {
        double duration = pcm.duration() / voice.pitchRatio();
        if (age < 0 || age >= duration) return 0;
        double envelope = Math.min(1, Math.min(age / .001, (duration - age) / .005));
        double pan = voice.pan();
        double balance = pcm.channels() == 1
                ? (channel == 0 ? Math.cos((pan + 1) * Math.PI / 4) : Math.sin((pan + 1) * Math.PI / 4))
                : (channel == 0 ? Math.min(1, 1 - pan) : Math.min(1, 1 + pan));
        return pcm.at(age * pcm.rate() * voice.pitchRatio(), channel, pcm.rate() * voice.pitchRatio() / outputRate)
                * voice.gain() * balance * envelope;
    }
}

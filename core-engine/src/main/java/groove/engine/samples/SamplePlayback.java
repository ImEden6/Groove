package groove.engine.samples;

import java.util.Objects;

/** Immutable resolved voice. PCM is already cropped/reversed and independently prefiltered. */
public record SamplePlayback(SampleVoice voice, SampleData pcm, LoopGeometry geometry) {
    public SamplePlayback(SampleVoice voice, SampleData pcm) {
        this(voice, pcm, LoopGeometry.resolve(pcm, voice, 48000));
    }

    public SamplePlayback {
        Objects.requireNonNull(voice);
        Objects.requireNonNull(pcm);
        if (geometry == null) geometry = LoopGeometry.NONE;
    }

    public double duration() {
        return pcm.duration() / voice.pitchRatio();
    }

    public void validateOutputRate(int rate) {
        double step = pcm.rate() * voice.pitchRatio() / rate;
        if (rate <= 0 || step > 16) throw new IllegalArgumentException("Sample resampling step exceeds 16");
    }

    public double value(double age, int channel, int outputRate) {
        return value(voice, pcm, geometry, age, channel, outputRate);
    }

    static double value(SampleVoice voice, SampleData pcm, double age, int channel, int outputRate) {
        return value(voice, pcm, LoopGeometry.resolve(pcm, voice, outputRate), age, channel, outputRate);
    }

    static double value(SampleVoice voice, SampleData pcm, LoopGeometry geometry, double age, int channel, int outputRate) {
        if (geometry != null && geometry.looped()) {
            double duration = 40.0;
            if (age < 0 || age >= duration) return 0;
            double envelope = Math.min(1, Math.min(age / .001, (duration - age) / .020));
            double pan = voice.pan();
            double balance = pcm.channels() == 1
                    ? (channel == 0 ? Math.cos((pan + 1) * Math.PI / 4) : Math.sin((pan + 1) * Math.PI / 4))
                    : (channel == 0 ? Math.min(1, 1 - pan) : Math.min(1, 1 + pan));
            double s = age * pcm.rate() * voice.pitchRatio();
            int lePrime = geometry.lePrime();
            int lsPrime = geometry.lsPrime();
            int p = geometry.period();
            int x = geometry.fadeFrames();
            double u = s < lePrime ? s : lsPrime + (s - lsPrime) - p * Math.floor((s - lsPrime) / p);
            double step = pcm.rate() * voice.pitchRatio() / outputRate;
            double sampleOut;
            if (x > 0 && u >= lePrime - x && u < lePrime) {
                double w = (u - (lePrime - x)) / (double) x;
                double c = Math.cos(Math.PI * w * 0.5);
                double d = Math.sin(Math.PI * w * 0.5);
                double n = Math.sqrt(1.0 + 2.0 * geometry.rho() * c * d);
                double a = c / n;
                double b = d / n;
                double su = pcm.at(u, channel, step);
                double sup = pcm.at(u - p, channel, step);
                sampleOut = a * su + b * sup;
            } else {
                sampleOut = pcm.at(u, channel, step);
            }
            return sampleOut * voice.gain() * balance * envelope;
        }
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

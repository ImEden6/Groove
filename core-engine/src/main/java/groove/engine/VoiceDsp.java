package groove.engine;

import groove.engine.samples.SamplePlayback;

/** Single-owner, preallocated voice DSP shared by live and offline schedulers. */
final class VoiceDsp {
    private final Biquad leftFilter = new Biquad(), rightFilter = new Biquad();
    private Tone tone;
    private SamplePlayback sample;
    private int sampleRate;
    private double leftPan, rightPan, increment;
    private double sampleGain, sampleDuration, sampleRateRatio, sampleStep;
    private final float[] stereoPcm = new float[2];

    void start(Tone tone, SamplePlayback sample, int sampleRate) {
        if ((tone == null) == (sample == null)) throw new IllegalArgumentException("Expected one voice source");
        this.tone = tone; this.sample = sample; this.sampleRate = sampleRate;
        double cutoff = tone != null ? tone.cutoffHz() : sample.voice().cutoffHz();
        double q = tone != null ? tone.resonanceQ() : sample.voice().resonanceQ();
        leftFilter.reset(); rightFilter.reset();
        leftFilter.setLowPass(cutoff, q, sampleRate); rightFilter.setLowPass(cutoff, q, sampleRate);
        if (tone != null) {
            double angle = (tone.pan() + 1) * Math.PI / 4;
            leftPan = Math.cos(angle); rightPan = Math.sin(angle);
            increment = tone.frequency() / sampleRate;
        } else {
            double pan = sample.voice().pan();
            if (sample.pcm().channels() == 1) {
                leftPan = Math.cos((pan + 1) * Math.PI / 4);
                rightPan = Math.sin((pan + 1) * Math.PI / 4);
            } else {
                leftPan = Math.min(1, 1 - pan);
                rightPan = Math.min(1, 1 + pan);
            }
            sampleGain = sample.voice().gain();
            sampleDuration = sample.duration();
            sampleRateRatio = sample.pcm().rate() * sample.voice().pitchRatio();
            sampleStep = sampleRateRatio / sampleRate;
        }
    }

    /** phase is supplied by the scheduler: accumulated offline, absolute-time for live seeks.
     *  duration bounds the tone envelope; samples carry their own half-open lifetime. */
    void add(double age, double duration, double phase, double fade, double[] out) {
        if (sample != null) {
            double left = 0, right = 0;
            if (age >= 0 && age < sampleDuration) {
                double envelope = Math.min(1, Math.min(age / .001, (sampleDuration - age) / .005));
                double frame = age * sampleRateRatio;
                if (sample.pcm().channels() == 1) {
                    float val = sample.pcm().at(frame, 0, sampleStep);
                    double v = val * sampleGain;
                    left = (v * leftPan) * envelope;
                    right = (v * rightPan) * envelope;
                } else {
                    sample.pcm().atStereo(frame, sampleStep, stereoPcm);
                    double v0 = stereoPcm[0] * sampleGain;
                    double v1 = stereoPcm[1] * sampleGain;
                    left = (v0 * leftPan) * envelope;
                    right = (v1 * rightPan) * envelope;
                }
            }
            out[0] += leftFilter.process(left) * fade;
            out[1] += rightFilter.process(right) * fade;
            return;
        }
        double remaining = duration - age;
        double raw = remaining <= 0 ? 0 : oscillator(tone.wave(), phase, increment, tone.pulseWidth());
        double envelope = Math.max(0, Math.min(1, Math.min(age / .005, remaining / .020)));
        double mono = leftFilter.process(raw) * tone.gain() * envelope * fade;
        out[0] += mono * leftPan; out[1] += mono * rightPan;
    }

    private static double oscillator(Tone.Wave wave, double phase, double step, double pulseWidth) {
        return switch (wave) {
            case SINE -> Math.sin(2 * Math.PI * phase);
            case SAW -> 2 * phase - 1 - polyBlep(phase, step);
            case PULSE -> {
                double d = Math.max(step, Math.min(1.0 - step, pulseWidth));
                double naive = phase < d ? 1.0 : -1.0;
                double phaseD = phase >= d ? phase - d : phase - d + 1.0;
                yield naive + polyBlep(phase, step) - polyBlep(phaseD, step) - (2 * d - 1);
            }
        };
    }
    private static double polyBlep(double phase, double step) {
        if (phase < step) { double x = phase / step; return x + x - x * x - 1; }
        if (phase > 1 - step) { double x = (phase - 1) / step; return x * x + x + x + 1; }
        return 0;
    }
}

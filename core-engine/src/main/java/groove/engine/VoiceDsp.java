package groove.engine;

import groove.engine.samples.SamplePlayback;

/** Single-owner, preallocated voice DSP shared by live and offline schedulers. */
final class VoiceDsp {
    private final Biquad leftFilter = new Biquad(), rightFilter = new Biquad();
    private Tone tone;
    private SamplePlayback sample;
    private double leftPan, rightPan, increment;
    private double sampleGain, sampleDuration, sampleRateRatio, sampleStep;
    private final float[] stereoPcm = new float[2];

    void start(Tone tone, SamplePlayback sample, int sampleRate) {
        start(tone, sample, sampleRate, sample != null ? sample.duration() : 0);
    }

    void start(Tone tone, SamplePlayback sample, int sampleRate, double duration) {
        if ((tone == null) == (sample == null)) throw new IllegalArgumentException("Expected one voice source");
        this.tone = tone; this.sample = sample;
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
            sampleDuration = sample.voice().loop() ? duration : sample.duration();
            sampleRateRatio = sample.pcm().rate() * sample.voice().pitchRatio();
            sampleStep = sampleRateRatio / sampleRate;
        }
    }

    /** Continues {@code other}'s filter history; call right after start. */
    void continueFrom(VoiceDsp other) {
        leftFilter.copyStateFrom(other.leftFilter); rightFilter.copyStateFrom(other.rightFilter);
    }

    /** phase is supplied by the scheduler: accumulated offline, absolute-time for live seeks.
     *  duration bounds the tone envelope; samples carry their own half-open lifetime. */
    void add(double age, double duration, double phase, double fade, double[] out) {
        if (sample != null) {
            double left = 0, right = 0;
            if (age >= 0 && age < sampleDuration) {
                boolean loop = sample.voice().loop() && sample.geometry() != null && sample.geometry().looped();
                double envelope = loop
                        ? Math.min(1, Math.min(age / .001, (sampleDuration - age) / .020))
                        : Math.min(1, Math.min(age / .001, (sampleDuration - age) / .005));
                if (loop) {
                    var geom = sample.geometry();
                    double s = age * sampleRateRatio;
                    int lePrime = geom.lePrime();
                    int lsPrime = geom.lsPrime();
                    int p = geom.period();
                    int x = geom.fadeFrames();
                    double u = s < lePrime ? s : lsPrime + (s - lsPrime) - p * Math.floor((s - lsPrime) / p);
                    if (x > 0 && u >= lePrime - x && u < lePrime) {
                        double w = (u - (lePrime - x)) / (double) x;
                        double c = Math.cos(Math.PI * w * 0.5);
                        double d = Math.sin(Math.PI * w * 0.5);
                        double n = Math.sqrt(1.0 + 2.0 * geom.rho() * c * d);
                        double a = c / n;
                        double b = d / n;
                        if (sample.pcm().channels() == 1) {
                            float valA = sample.pcm().at(u, 0, sampleStep);
                            float valB = sample.pcm().at(u - p, 0, sampleStep);
                            double v = (a * valA + b * valB) * sampleGain;
                            left = (v * leftPan) * envelope;
                            right = (v * rightPan) * envelope;
                        } else {
                            sample.pcm().atStereo(u, sampleStep, stereoPcm);
                            float u0 = stereoPcm[0], u1 = stereoPcm[1];
                            sample.pcm().atStereo(u - p, sampleStep, stereoPcm);
                            float up0 = stereoPcm[0], up1 = stereoPcm[1];
                            double v0 = (a * u0 + b * up0) * sampleGain;
                            double v1 = (a * u1 + b * up1) * sampleGain;
                            left = (v0 * leftPan) * envelope;
                            right = (v1 * rightPan) * envelope;
                        }
                    } else {
                        if (sample.pcm().channels() == 1) {
                            float val = sample.pcm().at(u, 0, sampleStep);
                            double v = val * sampleGain;
                            left = (v * leftPan) * envelope;
                            right = (v * rightPan) * envelope;
                        } else {
                            sample.pcm().atStereo(u, sampleStep, stereoPcm);
                            double v0 = stereoPcm[0] * sampleGain;
                            double v1 = stereoPcm[1] * sampleGain;
                            left = (v0 * leftPan) * envelope;
                            right = (v1 * rightPan) * envelope;
                        }
                    }
                } else {
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

package groove.engine.samples;

import java.util.Objects;

/**
 * Pure, deterministic loop geometry resolution for sustained sample loops.
 * Computed on the control thread during sample preparation.
 */
public record LoopGeometry(
        boolean looped,
        int lsPrime,
        int lePrime,
        int period,
        int fadeFrames,
        double rho,
        boolean clamped,
        boolean fallback
) {
    public static final LoopGeometry NONE = new LoopGeometry(false, 0, 0, 0, 0, 0.0, false, false);

    public static int calculateR(double step0) {
        int level = 0;
        double step = step0;
        while (step >= 2.0 && level < 4) {
            level++;
            step *= 0.5;
        }
        int twoToLevel = 1 << level;
        int band = (int) Math.ceil(Math.max(0.0, step0 / twoToLevel - 1.0) * 64.0);
        int radius = (int) Math.ceil(24.0 * (1.0 + band / 64.0));
        return (radius + 1) * twoToLevel + 48 * (twoToLevel - 1);
    }

    public static LoopGeometry resolve(SampleData prepared, SampleVoice voice, int outputRate) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(voice);
        if (!voice.loop()) {
            return NONE;
        }
        int n = prepared.frames();
        double step0 = (double) prepared.rate() * voice.pitchRatio() / outputRate;
        int r = calculateR(step0);

        int ls = (int) Math.floor(voice.loopStart() * n);
        int le = (int) Math.floor(voice.loopEnd() * n);
        int requestedFade = (int) Math.round(voice.loopFadeMs() / 1000.0 * prepared.rate() * voice.pitchRatio());
        int xPrime = Math.min(requestedFade, (int) Math.floor((le - ls) / 2.0));
        int lsPrime = Math.max(ls, r + xPrime);
        int lePrime = Math.min(le, n - r);
        int p = lePrime - lsPrime;
        boolean clamped = (lsPrime != ls) || (lePrime != le);

        if (p < 128) {
            return new LoopGeometry(false, lsPrime, lePrime, 0, 0, 0.0, clamped, true);
        }

        int x = Math.min(xPrime, p / 2);
        double rho = 0.0;
        if (x > 0) {
            int samples = Math.min(x, 8192);
            double sumXY = 0.0, sumX2 = 0.0, sumY2 = 0.0;
            int channels = prepared.channels();
            float[] pcm = prepared.pcm();
            for (int i = 0; i < samples; i++) {
                int k = samples == x ? i : (int) ((long) i * x / samples);
                int frameX = lePrime - x + k;
                int frameY = lsPrime - x + k;
                for (int c = 0; c < channels; c++) {
                    float xVal = pcm[frameX * channels + c];
                    float yVal = pcm[frameY * channels + c];
                    sumXY += (double) xVal * yVal;
                    sumX2 += (double) xVal * xVal;
                    sumY2 += (double) yVal * yVal;
                }
            }
            double denom = Math.sqrt(sumX2 * sumY2);
            if (denom > 0.0) {
                rho = sumXY / denom;
            }
        }
        rho = Math.max(-0.5, Math.min(1.0, rho));
        return new LoopGeometry(true, lsPrime, lePrime, p, x, rho, clamped, false);
    }
}

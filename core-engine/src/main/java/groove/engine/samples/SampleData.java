package groove.engine.samples;

/** Immutable, bounded interleaved PCM. Playback resamples to the output rate. */
public final class SampleData {
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_FLOATS = 2 * 1024 * 1024;
    private final int rate, channels;
    private final float[] pcm;
    private final float[][] levels;
    public SampleData(int rate, int channels, float[] pcm) {
        validate(rate, channels, pcm.length);
        this.rate = rate; this.channels = channels; this.pcm = pcm.clone();
        for (float value : this.pcm)
            if (!Float.isFinite(value) || Math.abs(value) > 1) throw new IllegalArgumentException("Invalid PCM amplitude");
        levels = buildLevels(this.pcm, channels);
    }
    public static void validate(int rate, int channels, long floats) {
        if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2 || floats <= 0
                || floats > MAX_FLOATS || floats % channels != 0 || floats / channels > rate * 10L)
            throw new IllegalArgumentException("Sample must be mono/stereo, 8–192 kHz, at most 10 seconds / 8 MiB decoded");
    }
    public int rate() { return rate; }
    public int channels() { return channels; }
    public int frames() { return pcm.length / channels; }
    public long bytes() { long size = 0; for (float[] level : levels) size += level.length * 4L; return size; }
    public double duration() { return (double) frames() / rate; }
    public float at(double frame, int channel) {
        if (!Double.isFinite(frame) || frame < 0 || frame >= frames()) return 0;
        int index = (int) frame;
        int c = channels == 1 ? 0 : channel;
        float a = pcm[index * channels + c];
        float b = index + 1 < frames() ? pcm[(index + 1) * channels + c] : 0;
        return (float) (a + (b - a) * (frame - index));
    }
    private static final int RADIUS = 24, RESOLUTION = 1024;
    private static final float[] KERNEL = buildKernel();
    private static final double[] HALF_RATE = halfRateWeights();
    private static final int BANDS = 64, PHASES = 256;
    private static final float[][] TABLES = buildTables();

    /** Bandlimited Kaiser interpolation, with prefiltered octaves for large rate changes.
     * step is source frames per output frame (supported playback range: 0 < step <= 16).
     * A 48-tap base kernel stretches to at most 97 coefficient slots after octave selection.
     * Passband ends at 80% of the smaller Nyquist limit; the transition ends at 100%.
     * Construction prepares all sample levels off the audio thread.
     */
    public float at(double frame, int channel, double step) {
        if (!Double.isFinite(step) || step <= 0 || step > 16) throw new IllegalArgumentException("Invalid resampling step");
        if (!Double.isFinite(frame) || frame < 0 || frame >= frames()) return 0;
        int c = channels == 1 ? 0 : channel;
        if (c < 0 || c >= channels) throw new IllegalArgumentException("Invalid channel");
        // Preserve original PCM exactly when no rate conversion is needed.
        if (step == 1 && frame == Math.floor(frame)) return pcm[(int) frame * channels + c];
        int level = 0;
        while (step >= 2) { level++; step *= .5; frame *= .5; }
        float[] data = levels[level];
        int band = (int)Math.ceil(Math.max(0, step - 1) * BANDS);
        int radius = (int)Math.ceil(RADIUS * (1 + band / (double)BANDS));
        int taps = radius * 2 + 1;
        int base = (int)frame, start = base - radius;
        double phase = (frame - base) * PHASES;
        int offset = (int)phase * taps;
        double blend = phase - (int)phase;
        float[] table = TABLES[band];
        double sumA = 0, sumB = 0;
        if (start >= 0 && start + taps <= data.length / channels) {
            int source = start * channels + c;
            double a0 = 0, a1 = 0, a2 = 0, a3 = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0;
            int tap = 0;
            for (; tap + 3 < taps; tap += 4, source += channels * 4) {
                double x0 = data[source], x1 = data[source + channels];
                double x2 = data[source + channels * 2], x3 = data[source + channels * 3];
                a0 += x0 * table[offset + tap]; b0 += x0 * table[offset + taps + tap];
                a1 += x1 * table[offset + tap + 1]; b1 += x1 * table[offset + taps + tap + 1];
                a2 += x2 * table[offset + tap + 2]; b2 += x2 * table[offset + taps + tap + 2];
                a3 += x3 * table[offset + tap + 3]; b3 += x3 * table[offset + taps + tap + 3];
            }
            sumA = a0 + a1 + a2 + a3; sumB = b0 + b1 + b2 + b3;
            for (; tap < taps; tap++, source += channels) {
                double value = data[source];
                sumA += value * table[offset + tap]; sumB += value * table[offset + taps + tap];
            }
        } else {
            for (int tap = 0; tap < taps; tap++) {
                int source = start + tap;
                if (source < 0 || source >= data.length / channels) continue;
                double value = data[source * channels + c];
                sumA += value * table[offset + tap]; sumB += value * table[offset + taps + tap];
            }
        }
        return (float)(sumA + (sumB - sumA) * blend);
    }
    private static float[][] buildTables() {
        float[][] tables = new float[BANDS + 1][];
        for (int band = 0; band <= BANDS; band++) {
            double scale = 1 + band / (double)BANDS;
            int radius = (int)Math.ceil(RADIUS * scale), taps = radius * 2 + 1;
            float[] table = tables[band] = new float[(PHASES + 1) * taps];
            for (int phase = 0; phase <= PHASES; phase++) {
                double total = 0;
                for (int tap = 0; tap < taps; tap++) {
                    double weight = kernel((tap - radius - phase / (double)PHASES) / scale);
                    table[phase * taps + tap] = (float)weight; total += weight;
                }
                for (int tap = 0; tap < taps; tap++) table[phase * taps + tap] /= (float)total;
            }
        }
        return tables;
    }
    private static double kernel(double distance) {
        double position = Math.abs(distance) * RESOLUTION;
        int index = (int)position;
        if (index >= KERNEL.length - 1) return 0;
        double a = KERNEL[index];
        return a + (KERNEL[index + 1] - a) * (position - index);
    }
    private static float[] buildKernel() {
        float[] table = new float[RADIUS * RESOLUTION + 1];
        double denominator = bessel(6.5);
        for (int i = 0; i < table.length; i++) {
            double distance = i / (double)RESOLUTION;
            double x = Math.PI * .9 * distance;
            double sinc = i == 0 ? 1 : Math.sin(x) / x;
            table[i] = (float)(sinc * bessel(6.5 * Math.sqrt(Math.max(0, 1 - distance * distance / (RADIUS * RADIUS)))) / denominator);
        }
        return table;
    }
    private static double[] halfRateWeights() {
        double[] weights = new double[RADIUS * 4 + 1];
        double sum = 0;
        for (int i = 0; i < weights.length; i++) { weights[i] = kernel((i - RADIUS * 2) * .5); sum += weights[i]; }
        for (int i = 0; i < weights.length; i++) weights[i] /= sum;
        return weights;
    }
    private static float[][] buildLevels(float[] original, int channels) {
        float[][] levels = new float[5][]; levels[0] = original;
        for (int level = 1; level < levels.length; level++) {
            float[] input = levels[level - 1];
            int frames = input.length / channels;
            float[] output = levels[level] = new float[((frames + 1) / 2) * channels];
            for (int frame = 0; frame < output.length / channels; frame++) {
                int center = frame * 2;
                int first = Math.max(0, center - RADIUS * 2), last = Math.min(frames - 1, center + RADIUS * 2);
                for (int c = 0; c < channels; c++) {
                    double sum = 0;
                    for (int source = first; source <= last; source++)
                        sum += input[source * channels + c] * HALF_RATE[source - center + RADIUS * 2];
                    output[frame * channels + c] = (float)sum;
                }
            }
        }
        return levels;
    }
    private static double bessel(double x) {
        double sum = 1, term = 1;
        for (int k = 1; k < 24; k++) { term *= x * x / (4 * k * k); sum += term; }
        return sum;
    }
    public float[] peaks(int bins) {
        if (bins < 1 || bins > 2048) throw new IllegalArgumentException("Invalid waveform resolution");
        float[] result = new float[bins];
        for (int i = 0; i < pcm.length; i++) {
            int bin = (int) ((long) (i / channels) * bins / frames());
            result[bin] = Math.max(result[bin], Math.abs(pcm[i]));
        }
        return result;
    }
}

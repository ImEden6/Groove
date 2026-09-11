package groove.engine.samples;

/** Immutable, bounded interleaved PCM. Playback resamples to the output rate. */
public final class SampleData {
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_FLOATS = 2 * 1024 * 1024;
    private final int rate, channels;
    private final float[] pcm;
    public SampleData(int rate, int channels, float[] pcm) {
        validate(rate, channels, pcm.length);
        this.rate = rate; this.channels = channels; this.pcm = pcm.clone();
        for (float value : this.pcm)
            if (!Float.isFinite(value) || Math.abs(value) > 1) throw new IllegalArgumentException("Invalid PCM amplitude");
    }
    public static void validate(int rate, int channels, long floats) {
        if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2 || floats <= 0
                || floats > MAX_FLOATS || floats % channels != 0 || floats / channels > rate * 10L)
            throw new IllegalArgumentException("Sample must be mono/stereo, 8–192 kHz, at most 10 seconds / 8 MiB decoded");
    }
    public int rate() { return rate; }
    public int channels() { return channels; }
    public int frames() { return pcm.length / channels; }
    public long bytes() { return pcm.length * 4L; }
    public double duration() { return (double) frames() / rate; }
    public float at(double frame, int channel) {
        if (!Double.isFinite(frame) || frame < 0 || frame >= frames()) return 0;
        int index = (int) frame;
        int c = channels == 1 ? 0 : channel;
        float a = pcm[index * channels + c];
        float b = index + 1 < frames() ? pcm[(index + 1) * channels + c] : 0;
        return (float) (a + (b - a) * (frame - index));
    }
    private static final int PHASES = 512, BANDS = 256, TAPS = 8;
    private static final float[][] SINC = buildSinc();

    /** Eight-tap Kaiser interpolation; step is source frames per output frame.
     * The cutoff follows the actual rate conversion, including the asset sample rate.
     * This short kernel reduces aliasing but cannot guarantee a 50 dB stopband.
     */
    public float at(double frame, int channel, double step) {
        if (!Double.isFinite(step) || step <= 0) throw new IllegalArgumentException("Invalid resampling step");
        if (!Double.isFinite(frame) || frame < 0 || frame >= frames()) return 0;
        if (step == 1 && frame == Math.floor(frame)) return pcm[(int) frame * channels + (channels == 1 ? 0 : channel)];
        int base = (int) frame;
        double phase = (frame - base) * PHASES;
        int phaseIndex = (int) phase;
        double blend = phase - phaseIndex;
        int band = Math.max(1, (int) (Math.min(1, 1 / step) * BANDS));
        float[] table = SINC[band];
        int c = channels == 1 ? 0 : channel;
        double sum = 0;
        for (int tap = 0; tap < TAPS; tap++) {
            int source = base + tap - 3;
            if (source < 0 || source >= frames()) continue;
            double a = table[phaseIndex * TAPS + tap];
            double b = table[(phaseIndex + 1) * TAPS + tap];
            sum += pcm[source * channels + c] * (a + (b - a) * blend);
        }
        return (float) sum;
    }
    private static float[][] buildSinc() {
        float[][] tables = new float[BANDS + 1][];
        double denominator = bessel(5);
        for (int band = 1; band <= BANDS; band++) {
            float[] table = tables[band] = new float[(PHASES + 1) * TAPS];
            double cutoff = band / (double) BANDS;
            for (int phase = 0; phase <= PHASES; phase++) {
                double sum = 0;
                for (int tap = 0; tap < TAPS; tap++) {
                    double distance = tap - 3 - phase / (double) PHASES;
                    double x = Math.PI * distance * cutoff;
                    double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(x) / x;
                    double window = bessel(5 * Math.sqrt(Math.max(0, 1 - distance * distance / 16))) / denominator;
                    double coefficient = cutoff * sinc * window;
                    table[phase * TAPS + tap] = (float) coefficient; sum += coefficient;
                }
                for (int tap = 0; tap < TAPS; tap++) table[phase * TAPS + tap] /= (float) sum;
            }
        }
        return tables;
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

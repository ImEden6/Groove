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

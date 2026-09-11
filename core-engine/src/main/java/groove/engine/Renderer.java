package groove.engine;

/** Single-owner renderer. All voices are allocated at construction; render performs no explicit allocation. */
public final class Renderer {
    private static final class Voice {
        Score.Note note;
        double phase, increment, left, right;
        int fadeFrame;
        final Biquad filter = new Biquad();
    }
    private final Score score;
    private final Voice[] voices;
    private final Voice[] tails;
    private final double[] stealFade;
    private int tailCursor;
    private long frame;
    private int nextNote;
    private long stolenVoices;

    public Renderer(Score score, int polyphony) {
        if (polyphony < 1 || polyphony > 256) throw new IllegalArgumentException("Polyphony must be 1..256");
        this.score = java.util.Objects.requireNonNull(score);
        voices = new Voice[polyphony];
        tails = new Voice[polyphony];
        for (int i = 0; i < polyphony; i++) { voices[i] = new Voice(); tails[i] = new Voice(); }
        stealFade = new double[Math.max(2, (int) Math.round(score.sampleRate() * .0025))];
        for (int i = 0; i < stealFade.length; i++) stealFade[i] = .5 * (1 + Math.cos(Math.PI * i / (stealFade.length - 1)));
    }

    public long position() { return frame; }
    public long stolenVoices() { return stolenVoices; }

    /** Writes interleaved stereo floats into the supplied buffer. */
    public void render(float[] output, int offsetFrames, int frames) {
        if (offsetFrames < 0 || frames < 0 || ((long) offsetFrames + frames) * 2 > output.length)
            throw new IllegalArgumentException("Output buffer too small");
        for (int i = 0; i < frames; i++, frame++) {
            for (Voice voice : voices)
                if (voice.note != null && frame >= voice.note.end()) voice.note = null;
            while (nextNote < score.size() && score.note(nextNote).start() <= frame)
                start(score.note(nextNote++));
            double left = 0, right = 0;
            for (int slot = 0; slot < voices.length + tails.length; slot++) {
                boolean tail = slot >= voices.length;
                Voice voice = tail ? tails[slot - voices.length] : voices[slot];
                if (voice.note == null) continue;
                long age = frame - voice.note.start();
                long remaining = voice.note.end() - frame;
                double envelope = Math.max(0, Math.min(1, Math.min(age / (score.sampleRate() * .005), remaining / (score.sampleRate() * .02))));
                double signal = voice.note.tone().wave() == Tone.Wave.SINE
                        ? Math.sin(2 * Math.PI * voice.phase)
                        : 2 * voice.phase - 1 - polyBlep(voice.phase, voice.increment);
                signal = voice.filter.process(signal);
                signal *= envelope * voice.note.tone().gain();
                if (tail) {
                    signal *= stealFade[voice.fadeFrame++];
                    if (voice.fadeFrame == stealFade.length || remaining <= 0) voice.note = null;
                }
                left += signal * voice.left; right += signal * voice.right;
                voice.phase += voice.increment; voice.phase -= Math.floor(voice.phase);
            }
            int index = (offsetFrames + i) * 2;
            output[index] = (float) Math.tanh(left);
            output[index + 1] = (float) Math.tanh(right);
        }
    }

    private void start(Score.Note note) {
        Voice selected = voices[0];
        for (Voice voice : voices) {
            if (voice.note == null) { selected = voice; break; }
            if (voice.note.start() < selected.note.start()) selected = voice;
        }
        if (selected.note != null) {
            stolenVoices++;
            int tail = tailCursor;
            tailCursor = (tail + 1) % tails.length;
            Voice replacement = tails[tail];
            tails[tail] = selected; selected.fadeFrame = 0;
            for (int i = 0; i < voices.length; i++) if (voices[i] == selected) { voices[i] = replacement; break; }
            selected = replacement;
        }
        selected.note = note;
        selected.phase = 0;
        selected.increment = note.tone().frequency() / score.sampleRate();
        selected.filter.setLowPass(note.tone().cutoffHz(), note.tone().resonanceQ(), score.sampleRate());
        selected.filter.reset();
        double angle = (note.tone().pan() + 1) * Math.PI / 4;
        selected.left = Math.cos(angle);
        selected.right = Math.sin(angle);
    }

    private static double polyBlep(double phase, double step) {
        if (phase < step) {
            double x = phase / step;
            return x + x - x * x - 1;
        }
        if (phase > 1 - step) {
            double x = (phase - 1) / step;
            return x * x + x + x + 1;
        }
        return 0;
    }
}

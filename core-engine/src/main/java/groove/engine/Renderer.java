package groove.engine;

/** Single-owner renderer. All voices are allocated at construction; render performs no explicit allocation. */
public final class Renderer {
    private static final class Voice {
        Score.Note note;
        double phase, increment, duration;
        int fadeFrame;
        final VoiceDsp dsp = new VoiceDsp();
    }
    private final Score score;
    private final Voice[] voices;
    private final Voice[] tails;
    private final double[] stealFade;
    private int tailCursor;
    private long frame;
    private int nextNote;
    private long stolenVoices;
    private final double[] stereo = new double[2];

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
            for (int slot = 0; slot < voices.length; slot++) {
                Voice voice = voices[slot];
                if (voice.note != null && frame >= voice.note.end()) {
                    if (voice.note.sample() != null) retire(slot); // Let the sample filter ring out through the bounded fade.
                    else voice.note = null;
                }
            }
            while (nextNote < score.size() && score.note(nextNote).start() <= frame)
                start(score.note(nextNote++));
            stereo[0] = stereo[1] = 0;
            for (int slot = 0; slot < voices.length + tails.length; slot++) {
                boolean tail = slot >= voices.length;
                Voice voice = tail ? tails[slot - voices.length] : voices[slot];
                if (voice.note == null) continue;
                long age = frame - voice.note.start();
                long remaining = voice.note.end() - frame;
                double fade = tail ? stealFade[voice.fadeFrame] : 1;
                voice.dsp.add(age / (double)score.sampleRate(), voice.duration, voice.phase, fade, stereo);
                if (tail) {
                    voice.fadeFrame++;
                    if (voice.fadeFrame == stealFade.length || (voice.note.sample() == null && remaining <= 0)) voice.note = null;
                }
                voice.phase += voice.increment; voice.phase -= Math.floor(voice.phase);
            }
            int index = (offsetFrames + i) * 2;
            output[index] = (float) Math.tanh(stereo[0]);
            output[index + 1] = (float) Math.tanh(stereo[1]);
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
            for (int i = 0; i < voices.length; i++) if (voices[i] == selected) { selected = retire(i); break; }
        }
        selected.note = note;
        selected.duration = (note.end() - note.start()) / (double)score.sampleRate();
        selected.phase = 0;
        selected.increment = note.tone() == null ? 0 : note.tone().frequency() / score.sampleRate();
        selected.dsp.start(note.tone(), note.sample(), score.sampleRate(), selected.duration);
    }

    private Voice retire(int slot) {
        Voice old = voices[slot], replacement = tails[tailCursor];
        voices[slot] = replacement; replacement.note = null;
        tails[tailCursor] = old; old.fadeFrame = 0;
        tailCursor = (tailCursor + 1) % tails.length;
        return replacement;
    }
}

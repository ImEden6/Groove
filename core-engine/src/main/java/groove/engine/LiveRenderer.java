package groove.engine;

/**
 * Bounded periodic scheduler/rendering path. Evaluating precompiled note intervals
 * directly makes late joins and seeks deterministic without replaying old attacks.
 * Single audio-thread owner; publish immutable programs from a compiler worker.
 */
public final class LiveRenderer {
    public static final int SAMPLE_RATE = 48000, MAX_VOICES = 32;
    public record Program(SessionState state, LoopPlan plan, java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples) {
        public Program(SessionState state, LoopPlan plan) { this(state, plan, java.util.Map.of()); }
        public Program { samples = java.util.Map.copyOf(samples); }
    }
    public record Timeline(Program current, Program pending) {
        public static Timeline compile(SessionTimeline.Snapshot snapshot) {
            return new Timeline(new Program(snapshot.current(), GraphCompiler.compile(snapshot.current().graph())),
                    snapshot.pending() == null ? null : new Program(snapshot.pending(), GraphCompiler.compile(snapshot.pending().graph())));
        }
    }
    private record Playback(VoiceProgram current, VoiceProgram pending) {}
    private static final class VoiceProgram {
        final SessionState state;
        final LoopPlan plan;
        final java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples;
        final Biquad[] filters;
        VoiceProgram(Program program) {
            state = program.state(); plan = program.plan(); samples = program.samples();
            filters = new Biquad[plan.size()];
            for (int i = 0; i < filters.length; i++) {
                filters[i] = new Biquad();
                if (plan.event(i).tone() != null) filters[i].setLowPass(plan.event(i).tone().cutoffHz(), SAMPLE_RATE);
            }
        }
    }
    private volatile Playback timeline;
    private boolean initialized;
    private long origin;
    private double elapsed;
    private long resyncs;
    private double fade;
    private Playback observed, previous;
    private double programBlend = 1;
    private final double[] currentStereo = new double[2];
    private final double[] prevStereo = new double[2];
    private final double[] tempStereo = new double[2];
    /** Control-thread only: prepare renderer-private filters before the volatile handoff.
     *  Programs remain shareable; only the audio owner mutates the prepared filters.
     *  Superseded playback state is released after its crossfade, without a map lookup. */
    public void publish(Timeline value) {
        java.util.Objects.requireNonNull(value);
        timeline = new Playback(new VoiceProgram(value.current()),
                value.pending() == null ? null : new VoiceProgram(value.pending()));
    }
    public long resyncs() { return resyncs; }
    /** Audio-owner call after an underrun; the next block rejoins the supplied playback time. */
    public void resynchronize() { initialized = false; elapsed = 0; fade = 0; resyncs++; }

    public void render(float[] output, int frames, long targetStartNanos) {
        if (frames < 0 || frames * 2L > output.length) throw new IllegalArgumentException("Output too small");
        if (!initialized) { origin = targetStartNanos; initialized = true; }
        double error = (targetStartNanos - origin) - elapsed;
        if (Math.abs(error) > 250_000_000) {
            origin = targetStartNanos; elapsed = 0; fade = 0; resyncs++;
            error = 0;
        }
        double step = 1e9 / SAMPLE_RATE;
        if (frames != 0) step += Math.max(-step * .001, Math.min(step * .001, error / frames));
        Playback currentTimeline = timeline;
        if (currentTimeline != observed) {
            previous = observed; observed = currentTimeline;
            programBlend = previous == null ? 1 : 0;
        }
        for (int f = 0; f < frames; f++, elapsed += step) {
            long now = origin + Math.round(elapsed);
            fade = Math.min(1, fade + 1.0 / 240);
            sampleTimeline(currentTimeline, now, currentStereo);
            double left = currentStereo[0] * programBlend;
            double right = currentStereo[1] * programBlend;
            if (previous != null && programBlend < 1) {
                sampleTimeline(previous, now, prevStereo);
                left += prevStereo[0] * (1 - programBlend);
                right += prevStereo[1] * (1 - programBlend);
            }
            output[f * 2] = (float) (Math.tanh(left) * fade);
            output[f * 2 + 1] = (float) (Math.tanh(right) * fade);
            programBlend = Math.min(1, programBlend + 1.0 / 240);
            if (programBlend == 1) previous = null;
        }
    }

    private void sampleTimeline(Playback timeline, long now, double[] out) {
        if (timeline == null) { out[0] = 0; out[1] = 0; return; }
        if (timeline.pending == null || now < timeline.pending.state.effectiveNanos()) {
            sample(timeline.current, now, out);
            return;
        }
        double blend = Math.min(1, (now - timeline.pending.state.effectiveNanos()) / 5_000_000.0);
        sample(timeline.pending, now, out);
        double pendL = out[0], pendR = out[1];
        if (blend < 1) {
            sample(timeline.current, now, tempStereo);
            out[0] = pendL * blend + tempStereo[0] * (1 - blend);
            out[1] = pendR * blend + tempStereo[1] * (1 - blend);
        } else {
            out[0] = pendL;
            out[1] = pendR;
        }
    }

    private void sample(VoiceProgram program, long now, double[] out) {
        if (program == null || !program.state.playing() || now < program.state.effectiveNanos()) {
            out[0] = 0; out[1] = 0; return;
        }
        double cycles = program.state.cycleAt(now);
        double phase = cycles - Math.floor(cycles);
        double secondsPerCycle = 240 / program.state.bpm();
        double sumL = 0, sumR = 0;
        int active = 0;
        Biquad[] toneFilters = program.filters;
        for (int i = 0; i < program.plan.size(); i++) {
            Event event = program.plan.event(i);
            if (event.sample() != null) {
                var voice = event.sample();
                var pcm = program.samples.get(voice.asset());
                if (pcm == null) continue; // Unresolved or mismatched assets are silent.
                double onset = Math.floor(cycles) + event.whole().start();
                if (onset > cycles) onset--;
                double duration = pcm.duration() / voice.pitchRatio();
                // One-shots may cross steps/cycles; bounded by the shared voice limit.
                while (onset >= program.state.anchorCycle()) {
                    double age = (cycles - onset) * secondsPerCycle;
                    if (age >= duration || active >= MAX_VOICES) break;
                    active++;
                    sumL += voice.value(pcm, age, 0);
                    sumR += voice.value(pcm, age, 1);
                    onset--;
                }
                if (active >= MAX_VOICES) break;
                continue;
            }
            Tone tone = event.tone();
            boolean withinWindow = phase >= event.whole().start() && phase < event.whole().end();
            double age = withinWindow ? (phase - event.whole().start()) * secondsPerCycle : 0;
            double raw = 0;
            if (withinWindow) {
                double oscillator = age * tone.frequency();
                oscillator -= Math.floor(oscillator);
                raw = tone.wave() == Tone.Wave.SINE ? Math.sin(2 * Math.PI * oscillator)
                        : 2 * oscillator - 1 - polyBlep(oscillator, tone.frequency() / SAMPLE_RATE);
            }
            // Always advance the filter, even while silent, so it rings down naturally
            // instead of freezing and clicking on the note's next attack.
            double filtered = toneFilters[i].process(raw);
            if (!withinWindow) continue;
            if (active++ >= MAX_VOICES) break;
            double remaining = (event.whole().end() - phase) * secondsPerCycle;
            double envelope = Math.min(1, Math.min(age / .005, remaining / .020));
            double angle = (tone.pan() + 1) * Math.PI / 4;
            double mono = filtered * tone.gain() * envelope;
            sumL += mono * Math.cos(angle);
            sumR += mono * Math.sin(angle);
        }
        out[0] = sumL;
        out[1] = sumR;
    }

    private static double polyBlep(double t, double dt) {
        if (t < dt) { double x = t / dt; return 2 * x - x * x - 1; }
        if (t > 1 - dt) { double x = (t - 1) / dt; return x * x + 2 * x + 1; }
        return 0;
    }
}

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
        final ActiveVoice[] voices = new ActiveVoice[MAX_VOICES];
        final ActiveVoice[] tails = new ActiveVoice[MAX_VOICES];
        final int[] events = new int[MAX_VOICES];
        final double[] onsets = new double[MAX_VOICES];
        int count, tailCursor;
        long lastNow = Long.MIN_VALUE, lastResync;
        VoiceProgram(Program program) {
            state = program.state(); plan = program.plan(); samples = program.samples();
            for (int i = 0; i < MAX_VOICES; i++) {
                voices[i] = new ActiveVoice(); tails[i] = new ActiveVoice();
            }
        }
        void candidate(int event, double onset) {
            int at = count;
            if (at == MAX_VOICES) {
                at--;
                if (onset <= onsets[at]) return;
            } else count++;
            while (at > 0 && onset > onsets[at - 1]) {
                events[at] = events[at - 1]; onsets[at] = onsets[at - 1]; at--;
            }
            events[at] = event; onsets[at] = onset;
        }
    }
    private static final class ActiveVoice {
        int event = -1, fadeFrame;
        double onset;
        boolean wanted;
        final Biquad left = new Biquad(), right = new Biquad();
        void start(VoiceProgram program, int index, double cycle) {
            event = index; onset = cycle; fadeFrame = 0;
            Event e = program.plan.event(index);
            double cutoff = e.tone() != null ? e.tone().cutoffHz() : e.sample().cutoffHz();
            double q = e.tone() != null ? e.tone().resonanceQ() : e.sample().resonanceQ();
            left.reset(); right.reset();
            left.setLowPass(cutoff, q, SAMPLE_RATE); right.setLowPass(cutoff, q, SAMPLE_RATE);
        }
    }
    private static final int STEAL_FRAMES = 120;
    private static final double[] STEAL_FADE = new double[STEAL_FRAMES];
    static {
        for (int i = 0; i < STEAL_FRAMES; i++)
            STEAL_FADE[i] = .5 * (1 + Math.cos(Math.PI * i / (STEAL_FRAMES - 1)));
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
        double secondsPerCycle = 240 / program.state.bpm();
        // A seek discards historical filter/tail state, just like a fresh late join.
        if (program.lastResync != resyncs || (program.lastNow != Long.MIN_VALUE && (now < program.lastNow || now - program.lastNow > 250_000_000L))) {
            for (ActiveVoice v : program.voices) v.event = -1;
            for (ActiveVoice v : program.tails) v.event = -1;
        }
        program.lastResync = resyncs;
        program.lastNow = now;
        program.count = 0;
        for (int i = 0; i < program.plan.size(); i++) {
            Event event = program.plan.event(i);
            double onset = Math.floor(cycles) + event.whole().start();
            if (onset > cycles) onset--;
            double duration;
            if (event.sample() != null) {
                var pcm = program.samples.get(event.sample().asset());
                if (pcm == null) continue;
                duration = pcm.duration() / event.sample().pitchRatio();
            } else duration = (event.whole().end() - event.whole().start()) * secondsPerCycle;
            for (int overlap = 0; overlap < MAX_VOICES && onset >= program.state.anchorCycle(); overlap++, onset--) {
                if ((cycles - onset) * secondsPerCycle >= duration) break;
                program.candidate(i, onset);
            }
        }
        for (ActiveVoice v : program.voices) {
            v.wanted = false;
            for (int i = 0; i < program.count; i++)
                if (v.event == program.events[i] && v.onset == program.onsets[i]) { v.wanted = true; break; }
        }
        for (int i = 0; i < MAX_VOICES; i++) {
            ActiveVoice v = program.voices[i];
            if (v.event < 0 || v.wanted) continue;
            // Preserve both channel filter histories by moving the entire voice into the tail ring.
            int tail = program.tailCursor;
            program.tailCursor = (tail + 1) % MAX_VOICES;
            program.voices[i] = program.tails[tail];
            program.voices[i].event = -1;
            program.tails[tail] = v; v.fadeFrame = 0;
        }
        for (int i = 0; i < program.count; i++) {
            boolean exists = false;
            for (ActiveVoice v : program.voices)
                if (v.event == program.events[i] && v.onset == program.onsets[i]) { exists = true; break; }
            if (!exists) for (ActiveVoice v : program.voices) if (v.event < 0) {
                v.start(program, program.events[i], program.onsets[i]); break;
            }
        }
        out[0] = 0; out[1] = 0;
        for (ActiveVoice v : program.voices) if (v.event >= 0) addVoice(program, v, cycles, secondsPerCycle, 1, out);
        for (ActiveVoice v : program.tails) if (v.event >= 0) {
            addVoice(program, v, cycles, secondsPerCycle, STEAL_FADE[v.fadeFrame++], out);
            if (v.fadeFrame == STEAL_FRAMES) v.event = -1;
        }
    }

    private void addVoice(VoiceProgram program, ActiveVoice v, double cycles, double secondsPerCycle,
                          double fade, double[] out) {
        Event event = program.plan.event(v.event);
        double age = (cycles - v.onset) * secondsPerCycle;
        if (event.sample() != null) {
            var voice = event.sample();
            var pcm = program.samples.get(voice.asset());
            out[0] += v.left.process(voice.value(pcm, age, 0, SAMPLE_RATE)) * fade;
            out[1] += v.right.process(voice.value(pcm, age, 1, SAMPLE_RATE)) * fade;
            return;
        }
        Tone tone = event.tone();
        double remaining = (event.whole().end() - event.whole().start()) * secondsPerCycle - age;
        double oscillator = age * tone.frequency();
        oscillator -= Math.floor(oscillator);
        double raw = remaining <= 0 ? 0 : tone.wave() == Tone.Wave.SINE ? Math.sin(2 * Math.PI * oscillator)
                : 2 * oscillator - 1 - polyBlep(oscillator, tone.frequency() / SAMPLE_RATE);
        double envelope = Math.max(0, Math.min(1, Math.min(age / .005, remaining / .020)));
        double mono = v.left.process(raw) * tone.gain() * envelope * fade;
        double angle = (tone.pan() + 1) * Math.PI / 4;
        out[0] += mono * Math.cos(angle); out[1] += mono * Math.sin(angle);
    }

    private static double polyBlep(double t, double dt) {
        if (t < dt) { double x = t / dt; return 2 * x - x * x - 1; }
        if (t > 1 - dt) { double x = (t - 1) / dt; return x * x + 2 * x + 1; }
        return 0;
    }
}

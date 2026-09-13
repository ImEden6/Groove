package groove.engine;

/**
 * Bounded rolling scheduler/rendering path. Worker-prepared absolute note intervals
 * anchor late joins and seeks. Stateful signal graphs silently replay bounded history.
 * Single audio-thread owner; publish immutable programs from a compiler worker.
 */
public final class LiveRenderer {
    public static final int SAMPLE_RATE = 48000, MAX_VOICES = 32;
    // Per VoiceProgram: four replay frames for each output frame gain three frames on
    // the moving clock. One second of history therefore takes about 1/3 second to join.
    private static final int HISTORY_FRAMES = SAMPLE_RATE, REPLAY_PER_FRAME = 4;
    public static final class Program {
        private final SessionState state;
        private final LoopPlan plan;
        private final java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples;
        private final LookaheadScheduler scheduler;
        private final Program[] sources;
        private final Program[] triggers;
        /** True for a trigger child specifically; the single source of truth VoiceProgram reads
         *  to derive its own needsVoicePool, instead of taking a second, separately-threaded flag
         *  that could drift out of sync with this one. */
        private final boolean isTriggerSource;
        public Program(SessionState state, LoopPlan plan) { this(state, plan, java.util.Map.of()); }
        public Program(SessionState state, LoopPlan plan, java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples) {
            this(state, plan, samples, false);
        }
        /** isTriggerSource picks the scheduler's lookback strategy: a trigger source needs a
         *  fixed cycle-bounded lookback (so a still-releasing ENVELOPE voice's onset stays
         *  visible) rather than the sample-duration-based history an audio source uses. */
        private Program(SessionState state, LoopPlan plan, java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples, boolean isTriggerSource) {
            this.state = state; this.plan = plan; this.samples = java.util.Map.copyOf(samples);
            this.isTriggerSource = isTriggerSource;
            SignalGraph signals = plan.signals();
            if (signals != null) {
                sources = new Program[signals.sourceCount()];
                for (int i = 0; i < sources.length; i++) sources[i] = new Program(state, signals.sourcePlan(i), this.samples, false);
                triggers = new Program[signals.triggerCount()];
                for (int i = 0; i < triggers.length; i++) triggers[i] = new Program(state, signals.triggerPlan(i), this.samples, true);
                scheduler = null;
            } else {
                sources = null;
                triggers = null;
                if (plan.pattern() == null) {
                    scheduler = null;
                } else if (isTriggerSource) {
                    scheduler = new LookaheadScheduler(plan.pattern(), SignalGraph.MAX_ENVELOPE_TAIL_CYCLES);
                } else {
                    double history = 0;
                    for (var pcm : samples.values()) history = Math.max(history, pcm.duration() / .25);
                    scheduler = new LookaheadScheduler(plan.pattern(), history, state.bpm());
                }
            }
            prepare(state.effectiveNanos());
        }
        public SessionState state() { return state; }
        public LoopPlan plan() { return plan; }
        public java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples() { return samples; }
        public void prepare(long serverNanos) {
            if (sources != null) for (Program source : sources) source.prepare(serverNanos);
            if (triggers != null) for (Program trigger : triggers) trigger.prepare(serverNanos);
            if (scheduler != null) scheduler.prepare(state.cycleAt(Math.max(serverNanos, state.effectiveNanos())));
        }
    }
    public record Timeline(Program current, Program pending) {
        /** Call periodically from one worker, including before late-join publication. */
        public void prepare(long serverNanos) { current.prepare(serverNanos); if (pending != null) pending.prepare(serverNanos); }
        public static Timeline compile(SessionTimeline.Snapshot snapshot) {
            return new Timeline(new Program(snapshot.current(), GraphCompiler.compile(snapshot.current().graph())),
                    snapshot.pending() == null ? null : new Program(snapshot.pending(), GraphCompiler.compile(snapshot.pending().graph())));
        }
    }
    private record Playback(VoiceProgram current, VoiceProgram pending) {}
    private static final class VoiceProgram {
        final SessionState state;
        final LoopPlan plan;
        final LookaheadScheduler scheduler;
        final SignalRuntime signals;
        final VoiceProgram[] sources;
        final double[][] sourceStereo;
        final VoiceProgram[] triggers;
        final LookaheadScheduler.Window[] triggerWindows;
        LookaheadScheduler.Window window;
        boolean missed;
        final boolean recoverEffects;
        boolean recovering;
        long recoveryOrigin, recoveryFrame;
        double recoveryGain = 1;
        final java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples;
        final ActiveVoice[] voices;
        final ActiveVoice[] tails;
        final int[] events;
        final Event[] data;
        final double[] onsets;
        int count, tailCursor;
        long lastNow = Long.MIN_VALUE, lastResync;
        /** A voice pool (ActiveVoice[MAX_VOICES]) is only needed by a leaf program that's
         *  actually mixed as audio; a trigger child is never mixed, only scheduled for onset
         *  timing, so it's excluded here even though (like any plain pattern Program) its own
         *  program.sources is null. Derived from Program's own isTriggerSource rather than
         *  passed as a second, separately-named flag, so the two can't drift out of sync. */
        VoiceProgram(Program program) {
            state = program.state(); plan = program.plan(); samples = program.samples(); scheduler = program.scheduler;
            signals = plan.signals() == null ? null : plan.signals().runtime(state);
            boolean stateful = false;
            if (plan.signals() != null) for (Graph.Node node : plan.signals().nodes)
                if (node.type() == NodeType.DELAY || node.type() == NodeType.FILTER) stateful = true;
            recoverEffects = stateful;
            int capacity = program.sources == null && !program.isTriggerSource ? MAX_VOICES : 0;
            voices = new ActiveVoice[capacity]; tails = new ActiveVoice[capacity];
            events = new int[capacity]; data = new Event[capacity]; onsets = new double[capacity];
            if (program.sources != null) {
                sources = new VoiceProgram[program.sources.length];
                sourceStereo = new double[sources.length][2];
                for (int i = 0; i < sources.length; i++) sources[i] = new VoiceProgram(program.sources[i]);
            } else { sources = null; sourceStereo = null; }
            if (program.triggers != null) {
                triggers = new VoiceProgram[program.triggers.length];
                triggerWindows = new LookaheadScheduler.Window[triggers.length];
                for (int i = 0; i < triggers.length; i++) triggers[i] = new VoiceProgram(program.triggers[i]);
            } else { triggers = null; triggerWindows = null; }
            for (int i = 0; i < capacity; i++) {
                voices[i] = new ActiveVoice(); tails[i] = new ActiveVoice();
            }
        }
        void candidate(int event, double onset, Event value) {
            int at = count;
            if (at == MAX_VOICES) {
                at--;
                if (onset <= onsets[at]) return;
            } else count++;
            while (at > 0 && onset > onsets[at - 1]) {
                events[at] = events[at - 1]; data[at] = data[at - 1]; onsets[at] = onsets[at - 1]; at--;
            }
            events[at] = event; data[at] = value; onsets[at] = onset;
        }
    }
    private static final class ActiveVoice {
        int event = -1, fadeFrame;
        Event data;
        double onset;
        boolean wanted;
        final Biquad left = new Biquad(), right = new Biquad();
        void start(Event e, int index, double cycle) {
            event = index; onset = cycle; fadeFrame = 0;
            data = e;
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
    private volatile long resyncs, scheduleMisses;
    private volatile long historyFrames, historyRecoveries;
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
    public long scheduleMisses() { return scheduleMisses; }
    public long historyFrames() { return historyFrames; }
    public long historyRecoveries() { return historyRecoveries; }
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
        capture(currentTimeline); capture(previous);
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
            if (ready(currentTimeline, now)) programBlend = Math.min(1, programBlend + 1.0 / 240);
            if (programBlend == 1) previous = null;
        }
    }

    private static void capture(Playback playback) {
        if (playback == null) return;
        capture(playback.current);
        if (playback.pending != null) capture(playback.pending);
    }

    private static void capture(VoiceProgram program) {
        // Keep the immutable history snapshot until replay catches the moving playback clock.
        if (program.recovering) return;
        if (program.scheduler != null) program.window = program.scheduler.window();
        if (program.sources != null) for (VoiceProgram source : program.sources) capture(source);
        if (program.triggers != null) for (VoiceProgram trigger : program.triggers) capture(trigger);
    }

    private static boolean covered(VoiceProgram program, double cycles) {
        if (program.scheduler != null && (program.window == null || !program.window.contains(cycles))) return false;
        if (program.sources != null) for (VoiceProgram source : program.sources) if (!covered(source, cycles)) return false;
        if (program.triggers != null) for (VoiceProgram trigger : program.triggers) if (!covered(trigger, cycles)) return false;
        return true;
    }

    private static void markMissed(VoiceProgram program) {
        program.missed = true;
        program.recovering = false;
        // Keep recovering==false and recoveryGain==1 as a joint invariant (see ready()) instead
        // of leaving recoveryGain stale until the next reset() call happens to fix it up.
        program.recoveryGain = 1;
        if (program.sources != null) for (VoiceProgram source : program.sources) markMissed(source);
        if (program.triggers != null) for (VoiceProgram trigger : program.triggers) markMissed(trigger);
    }

    private void sampleTimeline(Playback timeline, long now, double[] out) {
        if (timeline == null) { out[0] = 0; out[1] = 0; return; }
        if (timeline.pending == null || now < timeline.pending.state.effectiveNanos()) {
            sample(timeline.current, now, out);
            return;
        }
        double timeBlend = Math.min(1, (now - timeline.pending.state.effectiveNanos()) / 5_000_000.0);
        sample(timeline.pending, now, out);
        // sample() already applies recoveryGain, so the incoming weight is the product
        // of both fades. Use its complement for the outgoing program to preserve level
        // even when a short recovery finishes during the scheduled fade.
        double pendL = out[0] * timeBlend, pendR = out[1] * timeBlend;
        double oldWeight = 1 - timeBlend * timeline.pending.recoveryGain;
        if (oldWeight > 0) {
            sample(timeline.current, now, tempStereo);
            out[0] = pendL + tempStereo[0] * oldWeight;
            out[1] = pendR + tempStereo[1] * oldWeight;
        } else {
            out[0] = pendL;
            out[1] = pendR;
        }
    }

    private void sample(VoiceProgram program, long now, double[] out) {
        if (program == null || !program.state.playing() || now < program.state.effectiveNanos()) {
            out[0] = 0; out[1] = 0; return;
        }
        // Trigger children carry a scheduler/window but no ActiveVoice pool (capacity 0): the
        // voice-scheduling logic below assumes a real pool and isn't safe to run against a
        // zero-length voices array. They're only ever read via their own .window field from the
        // aggregate program's signal routing, never sampled directly, but guard defensively so a
        // future caller that does call sample() on one degrades to silence instead of crashing.
        if (program.voices.length == 0 && program.sources == null && program.triggers == null) {
            out[0] = 0; out[1] = 0; return;
        }
        double cycles = program.state.cycleAt(now);
        double secondsPerCycle = 240 / program.state.bpm();
        if (!covered(program, cycles)) {
            scheduleMisses++; markMissed(program); out[0] = 0; out[1] = 0; return;
        }
        if (program.missed || program.lastResync != resyncs || program.lastNow == Long.MIN_VALUE
                || now < program.lastNow || now - program.lastNow > 250_000_000L) {
            reset(program);
            if (program.recoverEffects) {
                double earliest = Math.max(program.state.anchorCycle(), historyStart(program));
                double availableSeconds = Math.max(0, (cycles - earliest) * secondsPerCycle);
                long frames = (long)Math.min(HISTORY_FRAMES, Math.floor(availableSeconds * SAMPLE_RATE));
                if (frames > 0) {
                    program.recoveryOrigin = now - Math.round(frames * 1e9 / SAMPLE_RATE);
                    program.recoveryFrame = 0;
                    program.recovering = true;
                    program.recoveryGain = 0;
                    historyRecoveries++;
                }
            }
        }
        program.missed = false;
        program.lastResync = resyncs;
        program.lastNow = now;
        if (program.sources != null) {
            int work = 0;
            while (program.recovering) {
                long replayNow = program.recoveryOrigin + Math.round(program.recoveryFrame * 1e9 / SAMPLE_RATE);
                if (replayNow >= now - 1e9 / SAMPLE_RATE / 2) { program.recovering = false; break; }
                if (work == REPLAY_PER_FRAME) { out[0] = out[1] = 0; return; }
                sampleSources(program, replayNow, out);
                program.recoveryFrame++; work++; historyFrames++;
            }
            sampleSources(program, now, out);
            program.recoveryGain = Math.min(1, program.recoveryGain + 1.0 / 240);
            out[0] *= program.recoveryGain; out[1] *= program.recoveryGain;
            return;
        }
        program.count = 0;
        if (program.scheduler != null) {
            for (int i = 0; i < program.window.size(); i++) {
                var entry = program.window.entry(i);
                Event event = entry.event();
                double onset = event.whole().start();
                if (onset > cycles) break;
                if (event.sample() != null && onset < program.state.anchorCycle()) continue;
                double duration = eventDuration(program, event, secondsPerCycle);
                if (duration < 0) continue;
                if ((cycles - onset) * secondsPerCycle < duration) program.candidate(entry.ordinal(), onset, event);
            }
        } else for (int i = 0; i < program.plan.size(); i++) {
            Event event = program.plan.event(i);
            double onset = Math.floor(cycles) + event.whole().start();
            if (onset > cycles) onset--;
            double duration = eventDuration(program, event, secondsPerCycle);
            if (duration < 0) continue;
            for (int overlap = 0; overlap < MAX_VOICES && onset >= program.state.anchorCycle(); overlap++, onset--) {
                if ((cycles - onset) * secondsPerCycle >= duration) break;
                program.candidate(i, onset, event);
            }
        }
        for (ActiveVoice v : program.voices) {
            v.wanted = false;
            for (int i = 0; i < program.count; i++)
                if (matches(v, program, i)) { v.wanted = true; break; }
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
                if (matches(v, program, i)) { exists = true; break; }
            if (!exists) for (ActiveVoice v : program.voices) if (v.event < 0) {
                v.start(program.data[i], program.events[i], program.onsets[i]); break;
            }
        }
        out[0] = 0; out[1] = 0;
        for (ActiveVoice v : program.voices) if (v.event >= 0) addVoice(program, v, cycles, secondsPerCycle, 1, out);
        for (ActiveVoice v : program.tails) if (v.event >= 0) {
            addVoice(program, v, cycles, secondsPerCycle, STEAL_FADE[v.fadeFrame++], out);
            if (v.fadeFrame == STEAL_FRAMES) v.event = -1;
        }
    }

    private static boolean ready(Playback playback, long now) {
        if (playback == null) return true;
        VoiceProgram active = playback.pending != null && now >= playback.pending.state.effectiveNanos()
                ? playback.pending : playback.current;
        return !active.recovering && active.recoveryGain == 1;
    }

    private void reset(VoiceProgram program) {
        for (ActiveVoice v : program.voices) v.event = -1;
        for (ActiveVoice v : program.tails) v.event = -1;
        if (program.signals != null) program.signals.reset();
        program.lastNow = Long.MIN_VALUE; program.lastResync = resyncs;
        program.missed = false; program.recovering = false; program.recoveryGain = 1;
        if (program.sources != null) for (VoiceProgram source : program.sources) reset(source);
        if (program.triggers != null) for (VoiceProgram trigger : program.triggers) reset(trigger);
    }

    private static double historyStart(VoiceProgram program) {
        double start = program.window == null ? Double.NEGATIVE_INFINITY : program.window.startCycle();
        if (program.sources != null) for (VoiceProgram source : program.sources) start = Math.max(start, historyStart(source));
        if (program.triggers != null) for (VoiceProgram trigger : program.triggers) start = Math.max(start, historyStart(trigger));
        return start;
    }

    private void sampleSources(VoiceProgram program, long now, double[] out) {
        for (int i = 0; i < program.sources.length; i++) sample(program.sources[i], now, program.sourceStereo[i]);
        for (int i = 0; i < program.triggers.length; i++) program.triggerWindows[i] = program.triggers[i].window;
        program.signals.process(program.sourceStereo, program.triggerWindows, out, now);
    }

    private static double eventDuration(VoiceProgram program, Event event, double secondsPerCycle) {
        if (event.sample() == null) return (event.whole().end() - event.whole().start()) * secondsPerCycle;
        var pcm = program.samples.get(event.sample().asset());
        return pcm == null ? -1 : pcm.duration() / event.sample().pitchRatio();
    }

    private static boolean matches(ActiveVoice voice, VoiceProgram program, int index) {
        if (voice.event < 0 || voice.event != program.events[index] || voice.onset != program.onsets[index]) return false;
        Event a = voice.data, b = program.data[index];
        return a.whole().equals(b.whole()) && java.util.Objects.equals(a.tone(), b.tone())
                && java.util.Objects.equals(a.sample(), b.sample());
    }

    private void addVoice(VoiceProgram program, ActiveVoice v, double cycles, double secondsPerCycle,
                          double fade, double[] out) {
        Event event = v.data;
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

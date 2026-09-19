package groove.engine;

/**
 * Bounded rolling scheduler/rendering path. Worker-prepared absolute note intervals
 * anchor late joins and seeks. Stateful signal graphs silently replay bounded history.
 * Single audio-thread owner; publish immutable programs from a compiler worker.
 */
public final class LiveRenderer {
    public static final int SAMPLE_RATE = 48000, MAX_VOICES = 32;
    // Per VoiceProgram: two replay frames for each output frame gain one frame on the
    // moving clock. One second of history therefore takes about one second to join.
    static final int HISTORY_FRAMES = SAMPLE_RATE, REPLAY_PER_FRAME = 2;
    /** Output frames one full history replay takes to catch the moving clock. */
    public static final int FULL_RECOVERY_FRAMES = (HISTORY_FRAMES + REPLAY_PER_FRAME - 2) / (REPLAY_PER_FRAME - 1);
    public static final class Program {
        private final SessionState state;
        private final LoopPlan plan;
        private final java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples;
        private final groove.engine.samples.PreparedSamples preparedSamples;
        private final LookaheadScheduler scheduler;
        private final Program[] sources;
        private final Program[] triggers;
        /** True for a trigger child specifically; the single source of truth VoiceProgram reads
         *  to derive its own needsVoicePool, instead of taking a second, separately-threaded flag
         *  that could drift out of sync with this one. */
        private final boolean isTriggerSource;
        public Program(SessionState state, LoopPlan plan) { this(state, plan, java.util.Map.of()); }
        public Program(SessionState state, LoopPlan plan, java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples) {
            this(state, plan, samples, false, null);
        }
        /** isTriggerSource picks the scheduler's lookback strategy: a trigger source needs a
         *  fixed cycle-bounded lookback (so a still-releasing ENVELOPE voice's onset stays
         *  visible) rather than the sample-duration-based history an audio source uses. */
        private Program(SessionState state, LoopPlan plan, java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples, boolean isTriggerSource,
                        groove.engine.samples.PreparedSamples prepared) {
            this.state = state; this.plan = plan; this.samples = java.util.Map.copyOf(samples);
            preparedSamples = prepared == null ? new groove.engine.samples.PreparedSamples(this.samples, plan.sampleVoices()) : prepared;
            this.isTriggerSource = isTriggerSource;
            SignalGraph signals = plan.signals();
            if (signals != null) {
                sources = new Program[signals.sourceCount()];
                for (int i = 0; i < sources.length; i++) sources[i] = new Program(state, signals.sourcePlan(i), this.samples, false, preparedSamples);
                triggers = new Program[signals.triggerCount()];
                for (int i = 0; i < triggers.length; i++) triggers[i] = new Program(state, signals.triggerPlan(i), this.samples, true, preparedSamples);
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
                    double secondsPerCycle = 240.0 / state.bpm();
                    for (var voice : plan.sampleVoices()) {
                        if (!voice.loop()) {
                            double lifetime = preparedSamples.lifetimeSeconds(voice, 0, secondsPerCycle);
                            if (lifetime > 0) history = Math.max(history, lifetime);
                        }
                    }
                    if (history > 40.0) history = 40.0;
                    final double spc = secondsPerCycle;
                    var durFn = (java.util.function.ToDoubleFunction<Event>) e ->
                            e.sample() == null ? (e.whole().end() - e.whole().start()) * spc
                                    : preparedSamples.lifetimeSeconds(e.sample(), e.whole().end() - e.whole().start(), spc);
                    scheduler = new LookaheadScheduler(plan.pattern(), history, state.bpm(), durFn);
                }
            }
            prepare(state.effectiveNanos());
        }
        public SessionState state() { return state; }
        public LoopPlan plan() { return plan; }
        public java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples() { return samples; }
        public long preparedSampleBytes() { return preparedSamples.bytes(); }
        public void prepare(long serverNanos) {
            if (sources != null) for (Program source : sources) source.prepare(serverNanos);
            if (triggers != null) for (Program trigger : triggers) trigger.prepare(serverNanos);
            if (scheduler != null) scheduler.prepare(state.cycleAt(Math.max(serverNanos, state.effectiveNanos())));
        }
    }
    public record Timeline(Program current, Program pending) {
        /** Resolve current and pending programs against one combined, bounded region bank. */
        public static Timeline withSamples(Timeline template, java.util.Map<groove.engine.samples.AssetRef, groove.engine.samples.SampleData> samples) {
            var bank = java.util.Map.copyOf(samples);
            var voices = new java.util.HashSet<>(template.current.plan.sampleVoices());
            if (template.pending != null) voices.addAll(template.pending.plan.sampleVoices());
            var prepared = new groove.engine.samples.PreparedSamples(bank, voices);
            return new Timeline(new Program(template.current.state, template.current.plan, bank, false, prepared),
                    template.pending == null ? null : new Program(template.pending.state, template.pending.plan, bank, false, prepared));
        }
        /** Call periodically from one worker, including before late-join publication. */
        public void prepare(long serverNanos) { current.prepare(serverNanos); if (pending != null) pending.prepare(serverNanos); }
        public static Timeline compile(SessionTimeline.Snapshot snapshot) {
            return new Timeline(new Program(snapshot.current(), GraphCompiler.compile(snapshot.current().graph())),
                    snapshot.pending() == null ? null : new Program(snapshot.pending(), GraphCompiler.compile(snapshot.pending().graph())));
        }
    }
    /** sessionKey is null when effect state must never carry into or out of this playback. */
    private record Playback(VoiceProgram current, VoiceProgram pending, Object sessionKey) {}
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
        /** Only top-level programs with stateful effects replay, so only they hold a lease. */
        final ReplayBudget.Lease lease;
        /** Reset with history to replay, but the replay has not started yet. */
        boolean unrecovered;
        /** Continued its predecessor's effect state at its first frame. */
        boolean carried;
        /** Sound thread: the ready program this one replaces, whose effect state it may continue. */
        VoiceProgram predecessor;
        /** Built on the control thread before publication: how to continue each candidate graph's effects. */
        final java.util.Map<SignalGraph, SignalRuntime.TransferPlan> transferPlans;
        final groove.engine.samples.PreparedSamples samples;
        final ActiveVoice[] voices;
        final ActiveVoice[] tails;
        final int[] events;
        final Event[] data;
        final double[] onsets;
        final int[] hashes, planHashes;
        int count, tailCursor;
        /** Cached voice selection, valid for cycles in [selectionFrom, selectionUntil) on selectedWindow. */
        boolean selectionValid;
        LookaheadScheduler.Window selectedWindow;
        double selectionFrom, selectionUntil;
        long lastNow = Long.MIN_VALUE, lastResync;
        /** A voice pool (ActiveVoice[MAX_VOICES]) is only needed by a leaf program that's
         *  actually mixed as audio; a trigger child is never mixed, only scheduled for onset
         *  timing, so it's excluded here even though (like any plain pattern Program) its own
         *  program.sources is null. Derived from Program's own isTriggerSource rather than
         *  passed as a second, separately-named flag, so the two can't drift out of sync. */
        VoiceProgram(Program program, LiveRenderer owner) {
            state = program.state(); plan = program.plan(); samples = program.preparedSamples; scheduler = program.scheduler;
            signals = plan.signals() == null ? null : plan.signals().runtime(state);
            // Only a program with a signal graph can carry effects, so only it pays for the map.
            transferPlans = plan.signals() == null ? java.util.Map.of() : new java.util.IdentityHashMap<>();
            boolean stateful = false;
            if (plan.signals() != null) for (Graph.Node node : plan.signals().nodes)
                if (node.type() == NodeType.DELAY || node.type() == NodeType.FILTER || node.type() == NodeType.REVERB) stateful = true;
            recoverEffects = stateful;
            lease = stateful ? new ReplayBudget.Lease(owner) : null;
            int capacity = program.sources == null && !program.isTriggerSource ? MAX_VOICES : 0;
            voices = new ActiveVoice[capacity]; tails = new ActiveVoice[capacity];
            events = new int[capacity]; data = new Event[capacity]; onsets = new double[capacity]; hashes = new int[capacity];
            planHashes = new int[scheduler == null && capacity > 0 ? plan.size() : 0];
            for (int i = 0; i < planHashes.length; i++) planHashes[i] = plan.event(i).matchHash();
            if (program.sources != null) {
                sources = new VoiceProgram[program.sources.length];
                sourceStereo = new double[sources.length][2];
                for (int i = 0; i < sources.length; i++) sources[i] = new VoiceProgram(program.sources[i], owner);
            } else { sources = null; sourceStereo = null; }
            if (program.triggers != null) {
                triggers = new VoiceProgram[program.triggers.length];
                triggerWindows = new LookaheadScheduler.Window[triggers.length];
                for (int i = 0; i < triggers.length; i++) triggers[i] = new VoiceProgram(program.triggers[i], owner);
            } else { triggers = null; triggerWindows = null; }
            for (int i = 0; i < capacity; i++) {
                voices[i] = new ActiveVoice(); tails[i] = new ActiveVoice();
            }
        }
        void candidate(int event, double onset, Event value, int hash) {
            int at = count;
            if (at == MAX_VOICES) {
                at--;
                if (onset <= onsets[at]) return;
            } else count++;
            while (at > 0 && onset > onsets[at - 1]) {
                events[at] = events[at - 1]; data[at] = data[at - 1]; onsets[at] = onsets[at - 1]; hashes[at] = hashes[at - 1]; at--;
            }
            events[at] = event; data[at] = value; onsets[at] = onset; hashes[at] = hash;
        }
    }
    private static final class ActiveVoice {
        int event = -1, fadeFrame, hash;
        Event data;
        double onset, duration;
        boolean wanted;
        final VoiceDsp dsp = new VoiceDsp();
        void start(Event e, int index, int hash, double cycle, double duration, groove.engine.samples.SamplePlayback sample) {
            event = index; this.hash = hash; onset = cycle; fadeFrame = 0;
            data = e; this.duration = duration;
            dsp.start(e.tone(), sample, SAMPLE_RATE, duration);
        }
    }
    private static final int STEAL_FRAMES = 120;
    private static final double[] STEAL_FADE = new double[STEAL_FRAMES];
    static {
        for (int i = 0; i < STEAL_FRAMES; i++)
            STEAL_FADE[i] = .5 * (1 + Math.cos(Math.PI * i / (STEAL_FRAMES - 1)));
    }
    private volatile Playback timeline;
    private volatile long publishEpoch;
    /** Publish epoch read with the timeline at the start of the current render. */
    private long renderEpoch;
    private boolean observedWasReady;
    private boolean initialized;
    private long origin;
    private double elapsed;
    private static final System.Logger LOGGER = System.getLogger("groove.engine.LiveRenderer");
    private volatile long resyncs, scheduleMisses;
    private volatile long historyFrames, historyRecoveries;
    private volatile long replayQueuedFrames, replayLeases, replayQueueDepth, replayMaxWaitFrames, replayReclaims, replayEvictions;
    private final ReplayBudget replayBudget;
    /** False keeps the original per-frame selection, for differential tests. */
    private final boolean cachedSelection;
    /** Tests only: voices started and stolen, to compare selection strategies. */
    long voiceStarts, voiceSteals, selections;
    /** Budget slot and its generation at registration; written by the budget on the sound thread. */
    int replaySlot = -1;
    long replayGeneration;
    private boolean replayCancelled;
    private volatile long loopFallbacks, loopClamps;
    private volatile long effectTransfers;
    /** Crossfade frames after a switch that carried effect state. The normal 240 until a shorter
     *  fade is validated against clicks; package-private so the fade trial can compare lengths. */
    static int carriedFadeFrames = 240;
    /** What the sound thread last had playing, so publish can plan transfers from it. */
    private volatile Playback renderedObserved, renderedPrevious;
    private double fade;
    private Playback observed, previous;
    private double programBlend = 1;
    private long lastFrameNanos;
    private final double[] currentStereo = new double[2];
    private final double[] prevStereo = new double[2];
    private final double[] tempStereo = new double[2];
    public LiveRenderer() { this(ReplayBudget.unlimited()); }

    /** Renderers sharing a budget must all render on the same sound thread. */
    public LiveRenderer(ReplayBudget replayBudget) { this(replayBudget, true); }

    LiveRenderer(ReplayBudget replayBudget, boolean cachedSelection) {
        this.replayBudget = java.util.Objects.requireNonNull(replayBudget);
        this.cachedSelection = cachedSelection;
    }

    /** Control-thread only: prepare renderer-private filters before the volatile handoff.
     *  Programs remain shareable; only the audio owner mutates the prepared filters.
     *  Superseded playback state is released after its crossfade, without a map lookup. */
    public void publish(Timeline value) { publish(value, null); }

    /**
     * Like {@link #publish(Timeline)}. Programs published under the same non-null sessionKey, at the
     * same playback position, continue the outgoing program's compatible effect state instead of
     * replaying history; see {@link SignalRuntime#transferPlan}. Pass a key that identifies the
     * session, never one shared by different patches.
     */
    public void publish(Timeline value, Object sessionKey) {
        java.util.Objects.requireNonNull(value);
        auditProgram(value.current());
        if (value.pending() != null) auditProgram(value.pending());
        var next = new Playback(new VoiceProgram(value.current(), this),
                value.pending() == null ? null : new VoiceProgram(value.pending(), this), sessionKey);
        if (sessionKey != null) planTransfers(next);
        // Epoch first: a render that sees the new timeline always sees its epoch
        publishEpoch++;
        timeline = next;
    }

    /** Control thread: plans from every graph that could still be playing when next takes over. */
    private void planTransfers(Playback next) {
        var candidates = new java.util.ArrayList<VoiceProgram>();
        for (Playback earlier : new Playback[] {timeline, renderedObserved, renderedPrevious})
            if (earlier != null && next.sessionKey.equals(earlier.sessionKey)) {
                candidates.add(earlier.current);
                if (earlier.pending != null) candidates.add(earlier.pending);
            }
        plan(next.current, candidates);
        if (next.pending != null) {
            candidates.add(next.current);
            plan(next.pending, candidates);
        }
    }

    private static void plan(VoiceProgram incoming, java.util.List<VoiceProgram> candidates) {
        SignalGraph to = incoming.plan.signals();
        if (to == null) return;
        for (VoiceProgram candidate : candidates) {
            SignalGraph from = candidate.plan.signals();
            if (from != null && !incoming.transferPlans.containsKey(from))
                incoming.transferPlans.put(from, SignalRuntime.transferPlan(from, to));
        }
    }

    private void auditProgram(Program p) {
        if (p == null || p.preparedSamples == null) return;
        long fb = 0, cl = 0;
        for (var sp : p.preparedSamples.voices().values()) {
            if (sp.geometry() != null) {
                if (sp.geometry().fallback()) fb++;
                if (sp.geometry().clamped()) cl++;
            }
        }
        loopFallbacks += fb;
        loopClamps += cl;
        LOGGER.log(System.Logger.Level.INFO, "Program loop statistics: fallbacks={0}, clamps={1}", fb, cl);
    }

    public long resyncs() { return resyncs; }
    public long scheduleMisses() { return scheduleMisses; }
    public long historyFrames() { return historyFrames; }
    public long historyRecoveries() { return historyRecoveries; }
    public long replayQueuedFrames() { return replayQueuedFrames; }
    public long replayLeases() { return replayLeases; }
    public long replayQueueDepth() { return replayQueueDepth; }
    public long replayMaxWaitFrames() { return replayMaxWaitFrames; }
    public long replayReclaims() { return replayReclaims; }
    public long replayEvictions() { return replayEvictions; }
    public long loopFallbacks() { return loopFallbacks; }
    /** Programs that continued an outgoing program's effect state instead of replaying. */
    public long effectTransfers() { return effectTransfers; }
    public long loopClamps() { return loopClamps; }
    public long reverbGuardHits() {
        long total = 0;
        if (observed != null) {
            total += countReverbGuardHits(observed.current);
            if (observed.pending != null) total += countReverbGuardHits(observed.pending);
        }
        if (previous != null) {
            total += countReverbGuardHits(previous.current);
            if (previous.pending != null) total += countReverbGuardHits(previous.pending);
        }
        return total;
    }

    private static long countReverbGuardHits(VoiceProgram p) {
        if (p == null) return 0;
        long hits = p.signals == null ? 0 : p.signals.reverbGuardHits();
        if (p.sources != null) for (VoiceProgram s : p.sources) hits += countReverbGuardHits(s);
        return hits;
    }
    /** Audio-owner call after an underrun; the next block rejoins the supplied playback time. */
    public void resynchronize() { initialized = false; elapsed = 0; fade = 0; resyncs++; }

    public void render(float[] output, int frames, long targetStartNanos) {
        if (frames < 0 || frames * 2L > output.length) throw new IllegalArgumentException("Output too small");
        // A reclaimed or evicted renderer must not keep replaying without a lease
        if (!replayBudget.heartbeat(this)) { dropReplays(observed); dropReplays(previous); }
        if (!initialized) { origin = targetStartNanos; initialized = true; }
        double error = (targetStartNanos - origin) - elapsed;
        if (Math.abs(error) > 250_000_000) {
            origin = targetStartNanos; elapsed = 0; fade = 0; resyncs++;
            error = 0;
        }
        double step = 1e9 / SAMPLE_RATE;
        if (frames != 0) step += Math.max(-step * .001, Math.min(step * .001, error / frames));
        Playback currentTimeline = timeline;
        renderEpoch = publishEpoch;
        if (currentTimeline != observed) {
            Playback audible = observedWasReady ? observed : previous;
            if (audible != null && currentTimeline != null && currentTimeline.sessionKey != null
                    && currentTimeline.sessionKey.equals(audible.sessionKey)) {
                VoiceProgram outgoing = lastRendered(audible);
                currentTimeline.current.predecessor = outgoing;
                if (currentTimeline.pending != null) currentTimeline.pending.predecessor = outgoing;
            }
            // Previous is the last timeline that was ready to mix, so a republish during a wait keeps it audible
            if (observedWasReady) {
                releaseReplay(previous);
                previous = observed;
                programBlend = previous == null ? 1 : 0;
            } else releaseReplay(observed);
            observed = currentTimeline;
            observedWasReady = false;
            renderedObserved = observed;
            renderedPrevious = previous;
        }
        capture(currentTimeline); capture(previous);
        if (hasQueued(currentTimeline) || hasQueued(previous)) replayBudget.decide();
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
            lastFrameNanos = now;
            if (ready(currentTimeline, now)) {
                observedWasReady = true;
                programBlend = Math.min(1, programBlend + 1.0 / (audibleCarried(currentTimeline, now) ? carriedFadeFrames : 240));
            }
            if (programBlend == 1 && previous != null) { releaseReplay(previous); previous = null; renderedPrevious = null; }
        }
        replayQueueDepth = replayBudget.queueDepth();
    }

    /** Sound thread: stops every replay and wait, and never requests again. For a renderer fading to silence. */
    public void cancelRecovery() {
        replayCancelled = true;
        cancelReplays(observed);
        cancelReplays(previous);
    }

    private void cancelReplays(Playback playback) {
        if (playback == null) return;
        cancelReplay(playback.current);
        cancelReplay(playback.pending);
    }

    /** Keeps the program silent: sampling without replay leaves an unrecovered program muted. */
    private void cancelReplay(VoiceProgram program) {
        if (program == null || program.lease == null || !(program.recovering || program.unrecovered)) return;
        replayBudget.release(program.lease);
        program.recovering = false;
        program.unrecovered = true;
        program.recoveryGain = 0;
    }

    private void dropReplays(Playback playback) {
        if (playback == null) return;
        dropReplay(playback.current);
        dropReplay(playback.pending);
    }

    /** The budget already freed the lease; restart so the next sample requeues and takes history at grant. */
    private void dropReplay(VoiceProgram program) {
        if (program == null || program.lease == null || !(program.recovering || program.unrecovered)) return;
        replayBudget.release(program.lease);
        reset(program);
        program.missed = true;
    }

    void countReplayReclaim() { replayReclaims++; }
    void countReplayEviction() { replayEvictions++; }

    private static boolean hasQueued(Playback playback) {
        return playback != null && (queued(playback.current) || queued(playback.pending));
    }

    private static boolean queued(VoiceProgram program) {
        return program != null && program.lease != null && program.lease.state == ReplayBudget.Lease.QUEUED;
    }

    private void releaseReplay(Playback playback) {
        if (playback == null) return;
        releaseReplay(playback.current);
        releaseReplay(playback.pending);
    }

    /** Frees the lease of a program that stops being sampled; a later sample starts over. */
    private void releaseReplay(VoiceProgram program) {
        if (program == null || program.lease == null || program.lease.state == ReplayBudget.Lease.IDLE) return;
        replayBudget.release(program.lease);
        program.recovering = false;
        program.unrecovered = false;
        program.recoveryGain = 1;
        program.missed = true;
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

    private void markMissed(VoiceProgram program) {
        program.missed = true;
        // A queued lease keeps its place; a started replay gives its lease back
        if (program.recovering) replayBudget.release(program.lease);
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
        double fadeNanos = timeline.pending.carried ? carriedFadeFrames * 1e9 / SAMPLE_RATE : 5_000_000.0;
        double timeBlend = Math.min(1, (now - timeline.pending.state.effectiveNanos()) / fadeNanos);
        // A scheduled change takes over from the program it replaces within this timeline.
        if (timeline.pending.lastNow == Long.MIN_VALUE && timeline.current.lastNow != Long.MIN_VALUE)
            timeline.pending.predecessor = timeline.current;
        sample(timeline.pending, now, out);
        // sample() already applies recoveryGain, so the incoming weight is the product
        // of both fades. Use its complement for the outgoing program to preserve level
        // even when a short recovery finishes during the scheduled fade.
        double pendL = out[0] * timeBlend, pendR = out[1] * timeBlend;
        double oldWeight = 1 - timeBlend * timeline.pending.recoveryGain;
        if (oldWeight > 0) {
            // Pending is already in effect, so a replay of current would end after pending replaces it
            sample(timeline.current, now, tempStereo, false);
            out[0] = pendL + tempStereo[0] * oldWeight;
            out[1] = pendR + tempStereo[1] * oldWeight;
        } else {
            releaseReplay(timeline.current);
            out[0] = pendL;
            out[1] = pendR;
        }
    }

    private void sample(VoiceProgram program, long now, double[] out) { sample(program, now, out, true); }

    /** mayReplay false: plays a program that is already running, but never starts or waits for a replay. */
    private void sample(VoiceProgram program, long now, double[] out, boolean mayReplay) {
        if (program == null || !program.state.playing() || now < program.state.effectiveNanos()) {
            // Not rendering, so it cannot carry; don't keep the outgoing program's buffers alive.
            if (program != null) program.predecessor = null;
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
            // A started replay restarts from the back of the queue
            if (program.recovering) replayBudget.release(program.lease);
            boolean first = program.lastNow == Long.MIN_VALUE && !program.missed;
            VoiceProgram from = program.predecessor;
            program.predecessor = null;
            reset(program);
            if (first && carryEffects(program, from, now)) {
                if (program.lease != null) replayBudget.release(program.lease);
            } else if (program.recoverEffects) {
                if (availableHistory(program, cycles, secondsPerCycle) > 0) {
                    program.unrecovered = true;
                    program.recoveryGain = 0;
                    // A waiting program keeps its place and takes the current epoch
                    if (program.lease.state != ReplayBudget.Lease.DROPPED) program.lease.epoch = renderEpoch;
                } else replayBudget.release(program.lease);
            }
        }
        program.missed = false;
        program.lastResync = resyncs;
        program.lastNow = now;
        if (program.unrecovered) {
            if (!mayReplay || replayCancelled) {
                replayBudget.release(program.lease);
                out[0] = 0; out[1] = 0; return;
            }
            // Dropped for an older epoch but still sampled, so this renderer still wants it
            if (program.lease.state == ReplayBudget.Lease.DROPPED && program.lease.epoch < renderEpoch) replayBudget.release(program.lease);
            if (program.lease.state == ReplayBudget.Lease.IDLE) {
                program.lease.epoch = renderEpoch;
                replayBudget.request(program.lease);
            }
            if (program.lease.state == ReplayBudget.Lease.DROPPED) { out[0] = 0; out[1] = 0; return; }
            if (program.lease.state == ReplayBudget.Lease.QUEUED) {
                // Waiting is silent; capture keeps the window fresh so it cannot miss
                replayQueuedFrames++;
                replayMaxWaitFrames = Math.max(replayMaxWaitFrames, ++program.lease.waitedFrames);
                out[0] = 0; out[1] = 0; return;
            }
            // History length is taken at grant, not at request
            long frames = availableHistory(program, cycles, secondsPerCycle);
            program.unrecovered = false;
            if (frames > 0) {
                program.recoveryOrigin = now - Math.round(frames * 1e9 / SAMPLE_RATE);
                program.recoveryFrame = 0;
                program.recovering = true;
                program.recoveryGain = 0;
                historyRecoveries++;
                replayLeases++;
                // A handed-over lease replays from the next frame, so one frame never holds two replays
                if (program.lease.waited) { out[0] = 0; out[1] = 0; return; }
            } else {
                replayBudget.release(program.lease);
                program.recoveryGain = 1;
            }
        }
        if (program.sources != null) {
            int work = 0;
            while (program.recovering) {
                long replayNow = program.recoveryOrigin + Math.round(program.recoveryFrame * 1e9 / SAMPLE_RATE);
                if (replayNow >= now - 1e9 / SAMPLE_RATE / 2) {
                    program.recovering = false;
                    replayBudget.release(program.lease);
                    break;
                }
                if (work == REPLAY_PER_FRAME) { out[0] = out[1] = 0; return; }
                sampleSources(program, replayNow, out);
                program.recoveryFrame++; work++; historyFrames++;
            }
            sampleSources(program, now, out);
            program.recoveryGain = Math.min(1, program.recoveryGain + 1.0 / 240);
            out[0] *= program.recoveryGain; out[1] *= program.recoveryGain;
            return;
        }
        if (!cachedSelection || !program.selectionValid || program.selectedWindow != program.window
                || !(cycles >= program.selectionFrom && cycles < program.selectionUntil))
            select(program, cycles, secondsPerCycle);
        out[0] = 0; out[1] = 0;
        for (ActiveVoice v : program.voices) if (v.event >= 0) addVoice(program, v, cycles, secondsPerCycle, 1, out);
        for (ActiveVoice v : program.tails) if (v.event >= 0) {
            addVoice(program, v, cycles, secondsPerCycle, STEAL_FADE[v.fadeFrame++], out);
            if (v.fadeFrame == STEAL_FRAMES) v.event = -1;
        }
    }

    /**
     * Chooses which events sound and starts or steals voices to match. The result only changes when some
     * event's "has started" or "is still sounding" test flips, so a cached selection is reused until the
     * earliest cycle where one could.
     */
    private void select(VoiceProgram program, double cycles, double secondsPerCycle) {
        double until = Double.POSITIVE_INFINITY;
        selections++;
        program.count = 0;
        if (program.scheduler != null) {
            for (int i = 0; i < program.window.size(); i++) {
                var entry = program.window.entry(i);
                Event event = entry.event();
                double onset = event.whole().start();
                if (onset > cycles) { until = Math.min(until, onset); break; }
                if (event.sample() != null && !event.sample().loop() && onset < program.state.anchorCycle()) continue;
                double duration = entry.durationSeconds();
                if (duration < 0) continue;
                if ((cycles - onset) * secondsPerCycle < duration) {
                    program.candidate(entry.ordinal(), onset, event, entry.matchHash());
                    until = Math.min(until, endBefore(onset, duration, secondsPerCycle));
                }
            }
        } else {
            double floor = Math.floor(cycles);
            until = Math.min(until, floor + 1);
            for (int i = 0; i < program.plan.size(); i++) {
                Event event = program.plan.event(i);
                double onset = floor + event.whole().start();
                if (onset > cycles) { until = Math.min(until, onset); onset--; }
                double duration = eventDuration(program, event, secondsPerCycle);
                if (duration < 0) continue;
                for (int overlap = 0; overlap < MAX_VOICES && (event.sample() != null && event.sample().loop() || onset >= program.state.anchorCycle()); overlap++, onset--) {
                    if ((cycles - onset) * secondsPerCycle >= duration) break;
                    program.candidate(i, onset, event, program.planHashes[i]);
                    until = Math.min(until, endBefore(onset, duration, secondsPerCycle));
                }
            }
        }
        program.selectionValid = true;
        program.selectedWindow = program.window;
        program.selectionFrom = cycles;
        program.selectionUntil = until;
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
            voiceSteals++;
        }
        for (int i = 0; i < program.count; i++) {
            boolean exists = false;
            for (ActiveVoice v : program.voices)
                if (matches(v, program, i)) { exists = true; break; }
            if (exists) continue;
            Event event = program.data[i];
            // One source of truth for duration, so a missing asset stays silent here too.
            double duration = eventDuration(program, event, secondsPerCycle);
            if (duration < 0) continue;
            for (ActiveVoice v : program.voices) if (v.event < 0) {
                v.start(event, program.events[i], program.hashes[i], program.onsets[i], duration,
                        event.sample() == null ? null : program.samples.get(event.sample()));
                voiceStarts++;
                break;
            }
        }
    }

    /** A cycle safely before (cycles - onset) * secondsPerCycle reaches duration, with room for rounding. */
    private static double endBefore(double onset, double duration, double secondsPerCycle) {
        double span = duration / secondsPerCycle;
        return onset + span - 1e-9 * (1 + Math.abs(onset) + span);
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
        program.selectionValid = false;
        program.missed = false; program.recovering = false; program.recoveryGain = 1; program.unrecovered = false;
        if (program.sources != null) for (VoiceProgram source : program.sources) reset(source);
        if (program.triggers != null) for (VoiceProgram trigger : program.triggers) reset(trigger);
    }

    /**
     * Continues {@code from}'s effect state in a freshly reset program, instead of replaying, when
     * both belong to one session and position: from rendered the frame before, is fully ready, and
     * agrees on the cycle position at the latest transport anchor within two frames.
     */
    private boolean carryEffects(VoiceProgram program, VoiceProgram from, long now) {
        if (from == null || program.signals == null || from.signals == null) return false;
        SignalRuntime.TransferPlan plan = program.transferPlans.get(from.plan.signals());
        if (plan == null || plan.carried() == 0) return false;
        double frameNanos = 1e9 / SAMPLE_RATE;
        if (from.lastNow == Long.MIN_VALUE || now <= from.lastNow || now - from.lastNow > 2 * frameNanos) return false;
        if (from.missed || from.recovering || from.unrecovered || from.recoveryGain != 1 || from.lastResync != resyncs) return false;
        // A tempo edit can arrive after its effective time. Compare at the change boundary:
        // the two rates legitimately diverge after it, even without a seek or render gap.
        long boundary = Math.max(program.state.effectiveNanos(), from.state.effectiveNanos());
        double apartSeconds = Math.abs(program.state.cycleAt(boundary) - from.state.cycleAt(boundary)) * 240 / program.state.bpm();
        if (apartSeconds > 2 / (double) SAMPLE_RATE) return false;
        program.signals.continueFrom(from.signals, plan);
        program.carried = true;
        effectTransfers++;
        return true;
    }

    private static boolean audibleCarried(Playback playback, long now) {
        VoiceProgram active = playback.pending != null && now >= playback.pending.state.effectiveNanos() ? playback.pending : playback.current;
        return active.carried;
    }

    /** The program of a playback that rendered most recently. */
    private static VoiceProgram lastRendered(Playback playback) {
        if (playback.pending != null && playback.pending.lastNow != Long.MIN_VALUE
                && playback.pending.lastNow >= playback.current.lastNow) return playback.pending;
        return playback.current;
    }

    private static long availableHistory(VoiceProgram program, double cycles, double secondsPerCycle) {
        double earliest = Math.max(program.state.anchorCycle(), historyStart(program));
        double availableSeconds = Math.max(0, (cycles - earliest) * secondsPerCycle);
        return (long) Math.min(HISTORY_FRAMES, Math.floor(availableSeconds * SAMPLE_RATE));
    }

    /** True while a program holds or waits for a lease, or the audible program is still fading in. */
    long publishEpoch() { return publishEpoch; }

    /** Sound thread: true once the latest published timeline's audible program has finished any wait, replay and fade-in. */
    public boolean mixReady() {
        Playback latest = observed;
        return latest != null && latest == timeline && ready(latest, lastFrameNanos);
    }

    boolean replayInProgress() {
        return leased(observed) || leased(previous) || (observed != null && !ready(observed, lastFrameNanos));
    }

    private static boolean leased(Playback playback) {
        return playback != null && (leased(playback.current) || leased(playback.pending));
    }

    private static boolean leased(VoiceProgram program) {
        return program != null && program.lease != null
                && (program.lease.state != ReplayBudget.Lease.IDLE || program.recovering);
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
        return program.samples.lifetimeSeconds(event.sample(), event.whole().end() - event.whole().start(), secondsPerCycle);
    }

    private static boolean matches(ActiveVoice voice, VoiceProgram program, int index) {
        if (voice.event < 0 || voice.event != program.events[index] || voice.onset != program.onsets[index]
                || voice.hash != program.hashes[index]) return false;
        Event a = voice.data, b = program.data[index];
        if (a == b) return true;
        return a.whole().equals(b.whole()) && java.util.Objects.equals(a.tone(), b.tone())
                && java.util.Objects.equals(a.sample(), b.sample());
    }

    private void addVoice(VoiceProgram program, ActiveVoice v, double cycles, double secondsPerCycle,
                          double fade, double[] out) {
        Event event = v.data;
        double age = (cycles - v.onset) * secondsPerCycle;
        double oscillator = event.tone() == null ? 0 : age * event.tone().frequency();
        oscillator -= Math.floor(oscillator);
        v.dsp.add(age, v.duration, oscillator, fade, out);
    }
}

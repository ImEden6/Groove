package groove.engine;

import java.util.ArrayList;
import java.util.Arrays;

/** Replay leasing regressions, build order stages 1 to 5. */
final class ReplayLeaseTests {
    private static int checks;
    private static final long JOIN_AT = 3_000_000_000L;
    private static final int RECOVERY_FRAMES =
            (LiveRenderer.HISTORY_FRAMES + LiveRenderer.REPLAY_PER_FRAME - 2) / (LiveRenderer.REPLAY_PER_FRAME - 1);
    private static final int FADE_FRAMES = 240;

    static void run() {
        budgetUnit();
        unlimitedMatchesPlain();
        oneRenderer();
        currentAndPendingOnOneRenderer();
        allocationFree();
        storms();
        republishKeepsPreviousAudible();
        freshJoinSilence();
        supersede();
        staleHolderReclaimed();
        staleWaiterRemoved();
        reclaimWhileRendering();
        evictWhileRendering();
        cancelRecoveryReleases();
        fastPaths();
        heartbeatIgnoresPlaybackTime();
        staleSlotReused();
        soundThreadHandoff();
        System.out.printf("Replay lease tests passed (%d checks).%n", checks);
    }

    private static void budgetUnit() {
        var budget = new ReplayBudget(2, 2);
        var a = new ReplayBudget.Lease();
        var b = new ReplayBudget.Lease();
        var c = new ReplayBudget.Lease();
        var d = new ReplayBudget.Lease();
        budget.request(a); budget.request(b); budget.request(c); budget.request(d);
        check(a.state == ReplayBudget.Lease.GRANTED && b.state == ReplayBudget.Lease.GRANTED, "First two requests granted");
        check(c.state == ReplayBudget.Lease.QUEUED && d.state == ReplayBudget.Lease.QUEUED, "Requests beyond k wait");
        check(budget.activeLeases() == 2 && budget.queueDepth() == 2, "k caps active leases");
        budget.release(b);
        check(c.state == ReplayBudget.Lease.GRANTED && d.state == ReplayBudget.Lease.QUEUED, "Release grants the queue head first");
        var e = new ReplayBudget.Lease();
        budget.request(e);
        budget.release(d);
        check(d.state == ReplayBudget.Lease.IDLE && budget.queueDepth() == 1 && e.state == ReplayBudget.Lease.QUEUED,
                "Releasing a queued lease removes it without granting past the cap");
        budget.release(d);
        check(budget.queueDepth() == 1, "Releasing an idle lease does nothing");
        budget.release(a);
        check(e.state == ReplayBudget.Lease.GRANTED, "Later request granted after earlier ones");
        budget.release(c); budget.release(e);
        check(budget.activeLeases() == 0 && budget.queueDepth() == 0, "Budget drains to empty");

        var full = new ReplayBudget(1, 1);
        var held = new ReplayBudget.Lease();
        full.request(held);
        for (int i = 0; i < ReplayBudget.PROGRAMS_PER_RENDERER; i++) full.request(new ReplayBudget.Lease());
        try {
            full.request(new ReplayBudget.Lease());
            throw new AssertionError("Queue overflow accepted");
        } catch (IllegalStateException expected) { checks++; }
        try {
            full.request(held);
            throw new AssertionError("Double request accepted");
        } catch (IllegalStateException expected) { checks++; }

        var confined = new ReplayBudget(1, 1);
        confined.request(new ReplayBudget.Lease());
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try { confined.request(new ReplayBudget.Lease()); } catch (Throwable t) { failure.set(t); }
        });
        other.start();
        try { other.join(); } catch (InterruptedException ex) { throw new AssertionError(ex); }
        check(failure.get() instanceof IllegalStateException, "A second thread on one budget fails loudly");

        var unlimited = ReplayBudget.unlimited();
        var free = new ReplayBudget.Lease[8];
        for (int i = 0; i < free.length; i++) { free[i] = new ReplayBudget.Lease(); unlimited.request(free[i]); }
        check(Arrays.stream(free).allMatch(l -> l.state == ReplayBudget.Lease.GRANTED), "Unlimited budget grants everything");
        check(unlimited.activeLeases() == 0 && unlimited.queueDepth() == 0, "Unlimited budget keeps no state");
        for (var lease : free) unlimited.release(lease);
        invalid(() -> new ReplayBudget(0, 1));
        invalid(() -> new ReplayBudget(1, 0));
    }

    private static void unlimitedMatchesPlain() {
        var timeline = joinTimeline(null);
        LiveRenderer plain = new LiveRenderer();
        LiveRenderer budgeted = new LiveRenderer(ReplayBudget.unlimited());
        plain.publish(timeline); budgeted.publish(timeline);
        float[] expected = new float[4096], actual = new float[4096];
        long now = JOIN_AT;
        for (int block = 0; block < 12; block++, now += Math.round(2048 * 1e9 / LiveRenderer.SAMPLE_RATE)) {
            plain.render(expected, 2048, now);
            budgeted.render(actual, 2048, now);
            check(Arrays.equals(expected, actual), "Unlimited budget renders bit-for-bit like the no-arg renderer, block " + block);
        }
        check(budgeted.historyRecoveries() == 1, "Late join onto a delay graph recovers once");
        check(budgeted.historyFrames() == plain.historyFrames() && budgeted.historyFrames() > 0, "Unlimited budget replays the same history");
        check(budgeted.replayLeases() == 1 && plain.replayLeases() == 1, "Each recovery counts one lease");
        check(budgeted.replayQueuedFrames() == 0 && budgeted.replayQueueDepth() == 0 && budgeted.replayMaxWaitFrames() == 0,
                "Unlimited budget never queues");
        check(budgeted.replayReclaims() == 0 && budgeted.replayEvictions() == 0, "Unlimited budget never reclaims or evicts");
        try {
            new LiveRenderer(null);
            throw new AssertionError("Null budget accepted");
        } catch (NullPointerException expectedRejection) { checks++; }
    }

    private static void oneRenderer() {
        var timeline = joinTimeline(null);
        var budget = new ReplayBudget(1);
        LiveRenderer leased = new LiveRenderer(budget);
        LiveRenderer plain = new LiveRenderer();
        leased.publish(timeline); plain.publish(timeline);
        float[] a = new float[2], b = new float[2];
        int bound = RECOVERY_FRAMES + FADE_FRAMES, done = -1;
        boolean identical = true;
        for (int f = 0; f < bound + 1000; f++) {
            long now = frameTime(f);
            long before = leased.historyFrames();
            leased.render(a, 1, now); plain.render(b, 1, now);
            identical &= Arrays.equals(a, b);
            check(leased.historyFrames() - before <= (long) budget.leases() * LiveRenderer.REPLAY_PER_FRAME,
                    "One renderer replays at most k * REPLAY_PER_FRAME per output frame");
            if (done < 0 && !leased.replayInProgress()) done = f;
        }
        check(identical, "A renderer that never waits sounds the same as the unlimited path");
        check(done > 0 && done <= bound, "One recovery completes within " + bound + " output frames, took " + done);
        check(leased.historyRecoveries() == 1 && leased.replayLeases() == 1, "One recovery, one lease");
        check(leased.replayQueuedFrames() == 0 && leased.replayMaxWaitFrames() == 0, "A lone recovery never waits");
        check(leased.scheduleMisses() == 0, "No schedule misses during the recovery");
        drained(budget, leased);
    }

    /** A pending commit already in effect at the join: only pending replays, since it replaces current. */
    private static void currentAndPendingOnOneRenderer() {
        for (int leases = 1; leases <= 2; leases++) {
            var run = pendingJoin(leases);
            check(run.maxWork <= LiveRenderer.REPLAY_PER_FRAME, "k = " + leases + ": only pending replays, max work " + run.maxWork);
            check(run.renderer.historyRecoveries() == 1 && run.renderer.replayLeases() == 1, "k = " + leases + ": current never takes a lease");
            check(run.renderer.replayQueuedFrames() == 0 && run.firstQueuedFrame < 0, "k = " + leases + ": nothing waits");
            check(run.renderer.scheduleMisses() == 0, "k = " + leases + ": no schedule misses");
            drained(run.budget, run.renderer);
        }
    }

    private record PendingRun(ReplayBudget budget, LiveRenderer renderer, long maxWork, int firstQueuedFrame) {}

    private static PendingRun pendingJoin(int leases) {
        Graph graph = SignalDemo.graph();
        SessionState pending = new SessionState(2, 2_000_000_000L, 1, 120, true, graph);
        var timeline = joinTimeline(pending);
        var budget = new ReplayBudget(leases, 1);
        LiveRenderer renderer = new LiveRenderer(budget);
        renderer.publish(timeline);
        float[] out = new float[2];
        long maxWork = 0;
        int firstQueued = -1;
        for (int f = 0; f < 3 * RECOVERY_FRAMES; f++) {
            long before = renderer.historyFrames();
            renderer.render(out, 1, frameTime(f));
            maxWork = Math.max(maxWork, renderer.historyFrames() - before);
            if (renderer.replayQueueDepth() > 0 && firstQueued < 0) firstQueued = f;
        }
        check(!renderer.replayInProgress(), "Pending join with k = " + leases + " finishes");
        return new PendingRun(budget, renderer, maxWork, firstQueued);
    }

    private static final int STORM_RENDERERS = 8, STORM_LEASES = 2;

    /** B8 topology: 8 renderers on one budget, all joining on the same frame. */
    private static void storms() {
        stormCase("n = 10", 2, false);
        stormCase("n = 16", STORM_RENDERERS, false);
        stormCase("n = 24", STORM_RENDERERS, true);
    }

    private static void stormCase(String label, int withCommit, boolean resyncWhilePending) {
        var budget = new ReplayBudget(STORM_LEASES, STORM_RENDERERS);
        var renderers = new LiveRenderer[STORM_RENDERERS];
        var timelines = new LiveRenderer.Timeline[STORM_RENDERERS];
        // Previous timelines stay audible through the storm, so their windows need preparing too
        var previousTimelines = new ArrayList<LiveRenderer.Timeline>();
        float[] out = new float[1024];
        long base = JOIN_AT;
        if (resyncWhilePending) {
            // Each renderer first plays a fresh timeline until it is ready, so it becomes previous
            var fresh = new SessionState(1, JOIN_AT, 0, 120, true, SignalDemo.graph());
            for (int r = 0; r < STORM_RENDERERS; r++) {
                renderers[r] = new LiveRenderer(budget);
                var t = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(fresh, null));
                t.prepare(JOIN_AT);
                renderers[r].publish(t);
                previousTimelines.add(t);
            }
            for (int block = 0; block < 48; block++) {
                long now = JOIN_AT + Math.round(block * 512 * 1e9 / LiveRenderer.SAMPLE_RATE);
                for (var renderer : renderers) renderer.render(out, 512, now);
            }
            for (var renderer : renderers) check(renderer.mixReady() && renderer.replayLeases() == 0, label + ": fresh timeline ready without replay");
            base = JOIN_AT + Math.round(48 * 512 * 1e9 / LiveRenderer.SAMPLE_RATE);
        }
        long commitAt = base - 1_000_000_000L;
        var current = new SessionState(1, 0, 0, 120, true, SignalDemo.graph());
        var pending = new SessionState(2, commitAt, current.cycleAt(commitAt), 120, true, SignalDemo.graph());
        int n = 0, needed = 0;
        for (int r = 0; r < STORM_RENDERERS; r++) {
            if (renderers[r] == null) renderers[r] = new LiveRenderer(budget);
            boolean commit = r < withCommit;
            timelines[r] = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(current, commit ? pending : null));
            timelines[r].prepare(base);
            renderers[r].publish(timelines[r]);
            n += (commit ? 2 : 1) + (resyncWhilePending ? 1 : 0);
            needed += 1 + (resyncWhilePending ? 1 : 0);
        }
        if (resyncWhilePending) for (var renderer : renderers) renderer.resynchronize();
        budget.recordGrants(4 * n);
        int rounds = (n + STORM_LEASES - 1) / STORM_LEASES;
        int bound = rounds * RECOVERY_FRAMES + FADE_FRAMES;
        long maxWork = 0;
        int done = -1;
        for (int f = 0; f < bound + 2000 && done < 0; f++) {
            long now = base + Math.round(f * 1e9 / LiveRenderer.SAMPLE_RATE);
            if (f % 2048 == 0 && f > 0) {
                for (var t : timelines) t.prepare(now);
                for (var t : previousTimelines) t.prepare(now);
            }
            long work = 0;
            boolean busy = false;
            for (var renderer : renderers) {
                long before = renderer.historyFrames();
                renderer.render(out, 1, now);
                work += renderer.historyFrames() - before;
            }
            for (var renderer : renderers) busy |= renderer.replayInProgress();
            check(budget.activeLeases() <= STORM_LEASES, label + ": at most k leases active");
            check(work <= (long) STORM_LEASES * LiveRenderer.REPLAY_PER_FRAME, label + ": summed replay work " + work + " at frame " + f);
            maxWork = Math.max(maxWork, work);
            if (!busy) done = f;
        }
        check(done >= 0 && done <= bound, label + ": every program done within " + bound + " frames, took " + done);
        check(maxWork == (long) STORM_LEASES * LiveRenderer.REPLAY_PER_FRAME, label + ": the storm uses both leases at once");
        long[] grants = budget.grantLog();
        for (int i = 1; i < grants.length; i++) check(grants[i] > grants[i - 1], label + ": grants follow request order");
        long leases = 0, queued = 0;
        for (var renderer : renderers) {
            leases += renderer.replayLeases(); queued += renderer.replayQueuedFrames();
            check(renderer.scheduleMisses() == 0, label + ": no window missed while queued");
            check(renderer.mixReady(), label + ": every renderer ends ready to mix");
        }
        check(leases == grants.length && leases == needed, label + ": " + leases + " grants for " + n + " programs, current of a commit never replays");
        check(queued > 0, label + ": programs waited");
        System.out.printf("  %s: %d grants, done at frame %d of %d%n", label, leases, done, bound);
        for (var renderer : renderers) check(!renderer.replayInProgress(), label + ": renderer holds no lease");
        drained(budget, renderers[0]);
    }

    /** A continuous stateful graph, so a wait with nothing audible would show in RMS. */
    private static Graph continuous() {
        Graph demo = SignalDemo.graph();
        var nodes = new java.util.ArrayList<Graph.Node>();
        for (var node : demo.nodes())
            nodes.add(node.id().equals("rhythm") ? new Graph.Node("rhythm", NodeType.EUCLID, java.util.Map.of("steps", 8.0, "pulses", 8.0)) : node);
        return new Graph(demo.version(), nodes, demo.edges());
    }

    private static void republishKeepsPreviousAudible() {
        var budget = new ReplayBudget(1, 2);
        var listener = new LiveRenderer(budget);
        var blocker = new LiveRenderer(budget);
        Graph graph = continuous();
        var fresh = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(new SessionState(1, JOIN_AT, 0, 120, true, graph), null));
        fresh.prepare(JOIN_AT);
        listener.publish(fresh);
        float[] out = new float[2], scratch = new float[2];
        int f = 0;
        for (; f < 24_000; f++) listener.render(out, 1, frameTime(f));
        check(listener.mixReady(), "Listener ready on its fresh timeline");
        long switchAt = frameTime(f);
        var joined = joinAt(switchAt, graph);
        var republished = joinAt(switchAt, graph);
        blocker.publish(joined);
        listener.publish(republished);
        double sum = 0, windowSum = 0;
        int waited = 0, windowFrames = 0, windows = 0;
        boolean quietWindow = false;
        for (int i = 0; i < 3 * RECOVERY_FRAMES; i++, f++) {
            long now = frameTime(f);
            if (i % 2048 == 0) { fresh.prepare(now); joined.prepare(now); republished.prepare(now); }
            blocker.render(scratch, 1, now);
            long queuedBefore = listener.replayQueuedFrames();
            listener.render(out, 1, now);
            if (listener.replayQueuedFrames() > queuedBefore) {
                double energy = (out[0] * out[0] + out[1] * out[1]) / 2;
                sum += energy; windowSum += energy; waited++;
                if (++windowFrames == 2400) {
                    quietWindow |= Math.sqrt(windowSum / windowFrames) <= 0.01;
                    windows++; windowSum = 0; windowFrames = 0;
                }
            }
        }
        check(waited > RECOVERY_FRAMES / 2, "Listener waited behind the blocker for " + waited + " frames");
        check(windows > 0 && !quietWindow, "Previous stays above -40 dBFS in every 50 ms of the wait");
        check(Math.sqrt(sum / waited) > 0.01, "Previous stays audible through the wait");
        check(listener.mixReady() && !listener.replayInProgress(), "Republished timeline takes over after its replay");
        check(listener.scheduleMisses() == 0 && blocker.scheduleMisses() == 0, "No misses while waiting");
        drained(budget, listener);
    }

    private static void freshJoinSilence() {
        var budget = new ReplayBudget(1, 2);
        var listener = new LiveRenderer(budget);
        var blocker = new LiveRenderer(budget);
        Graph graph = continuous();
        var joined = joinAt(JOIN_AT, graph);
        var fresh = joinAt(JOIN_AT, graph);
        blocker.publish(joined);
        listener.publish(fresh);
        float[] out = new float[2], scratch = new float[2];
        int silentWhileQueued = 0, waited = 0, firstSound = -1;
        int bound = 2 * RECOVERY_FRAMES + FADE_FRAMES + 1;
        for (int f = 0; f < bound + 1000; f++) {
            long now = frameTime(f);
            if (f % 2048 == 0) { joined.prepare(now); fresh.prepare(now); }
            blocker.render(scratch, 1, now);
            long queuedBefore = listener.replayQueuedFrames();
            listener.render(out, 1, now);
            if (listener.replayQueuedFrames() > queuedBefore) {
                waited++;
                if (out[0] == 0 && out[1] == 0) silentWhileQueued++;
            }
            if (firstSound < 0 && (out[0] != 0 || out[1] != 0)) firstSound = f;
        }
        check(waited > 0 && silentWhileQueued == waited, "A fresh join is silent while queued");
        check(firstSound > waited && firstSound <= bound, "Fresh-join dropout ends within " + bound + " frames, was " + firstSound);
        check(listener.mixReady(), "Fresh join ends ready");
        drained(budget, listener);
    }

    private static void supersede() {
        Graph graph = SignalDemo.graph();
        float[] out = new float[2];
        // Superseded while queued: never granted, and the newer timeline requests afresh
        var budget = new ReplayBudget(1, 2);
        budget.recordGrants(16);
        var listener = new LiveRenderer(budget);
        var blocker = new LiveRenderer(budget);
        blocker.publish(joinAt(JOIN_AT, graph));
        listener.publish(joinAt(JOIN_AT, graph));
        int f = 0;
        for (; f < 10; f++) { blocker.render(out, 1, frameTime(f)); listener.render(out, 1, frameTime(f)); }
        check(budget.queueDepth() == 1 && !listener.mixReady(), "Listener queued behind blocker");
        listener.publish(joinAt(frameTime(f), graph));
        for (int i = 0; i < RECOVERY_FRAMES + FADE_FRAMES + 10; i++, f++) blocker.render(out, 1, frameTime(f));
        check(budget.activeLeases() == 0 && budget.queueDepth() == 0, "Superseded waiter is not granted when the holder releases");
        check(listener.replayLeases() == 0, "Superseded waiter never replays");
        for (int i = 0; i < RECOVERY_FRAMES + FADE_FRAMES + 10; i++, f++) listener.render(out, 1, frameTime(f));
        check(listener.replayLeases() == 1 && listener.mixReady(), "Newer timeline is granted and completes");
        check(budget.grantLog().length == 2, "Only the blocker and the newer timeline were granted");
        drained(budget, listener);

        // Superseded while replaying: the lease is released on the next render
        var active = new ReplayBudget(1, 1);
        var holder = new LiveRenderer(active);
        holder.publish(joinAt(JOIN_AT, graph));
        f = 0;
        for (; f < 100; f++) holder.render(out, 1, frameTime(f));
        check(active.activeLeases() == 1 && holder.historyRecoveries() == 1, "Holder replaying");
        holder.publish(joinAt(frameTime(f), graph));
        long before = holder.historyFrames();
        holder.render(out, 1, frameTime(f++));
        check(holder.historyRecoveries() == 2 && active.activeLeases() == 1, "Republish hands the lease to the new timeline in one render");
        check(holder.historyFrames() - before <= LiveRenderer.REPLAY_PER_FRAME, "Handover render replays one program only");
        for (int i = 0; i < RECOVERY_FRAMES + FADE_FRAMES + 10; i++, f++) holder.render(out, 1, frameTime(f));
        check(holder.mixReady(), "New timeline completes");
        drained(active, holder);

        // Resync while queued keeps the request and still completes
        var resync = new ReplayBudget(1, 2);
        var first = new LiveRenderer(resync);
        var second = new LiveRenderer(resync);
        first.publish(joinAt(JOIN_AT, graph));
        second.publish(joinAt(JOIN_AT, graph));
        f = 0;
        for (; f < 10; f++) { first.render(out, 1, frameTime(f)); second.render(out, 1, frameTime(f)); }
        second.resynchronize();
        for (int i = 0; i < 2 * RECOVERY_FRAMES + FADE_FRAMES + 10; i++, f++) { first.render(out, 1, frameTime(f)); second.render(out, 1, frameTime(f)); }
        check(second.mixReady() && second.replayLeases() == 1, "Resync while queued requeues and completes once");
        drained(resync, second);
    }

    private static LiveRenderer.Timeline joinAt(long now, Graph graph) {
        var timeline = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(new SessionState(1, 0, 0, 120, true, graph), null));
        timeline.prepare(now);
        return timeline;
    }

    private static void allocationFree() {
        var counter = AllocHelper.bean();
        if (counter == null) return;
        float[] out = new float[1024];
        for (int warm = 0; warm < 3; warm++) renderQueuedJoin(out, null);
        long bytes = renderQueuedJoin(out, counter);
        check(bytes == 0, "Queued, granted and released replay renders without allocating: " + bytes);
    }

    /** Covers waiting, grants, registration, eviction and stale reclaim in one measured run. */
    private static long renderQueuedJoin(float[] out, com.sun.management.ThreadMXBean counter) {
        long[] clock = {0};
        var budget = new ReplayBudget(1, 2, () -> clock[0]);
        LiveRenderer first = new LiveRenderer(budget), second = new LiveRenderer(budget), third = new LiveRenderer(budget);
        first.publish(joinTimeline(null));
        second.publish(joinTimeline(null));
        third.publish(joinTimeline(null));
        long id = Thread.currentThread().threadId();
        long before = counter == null ? 0 : counter.getThreadAllocatedBytes(id);
        // second stops at block 40; first is reclaimed into a lease once stale, then needs one full recovery and its fade
        int blocks = 40 + (int) (ReplayBudget.STALE_NANOS / (512 * FRAME_NANOS)) + 2 + (LiveRenderer.FULL_RECOVERY_FRAMES + FADE_FRAMES) / 512 + 2;
        for (int block = 0; block < blocks; block++) {
            long now = frameTime(block * 512);
            clock[0] = now;
            first.render(out, 512, now);
            // second stops while holding its lease, so first is granted only through a stale reclaim
            if (block < 40) second.render(out, 512, now);
            // third registers once with no free slot and evicts first
            if (block == 20) third.render(out, 512, now);
        }
        long bytes = counter == null ? 0 : counter.getThreadAllocatedBytes(id) - before;
        check(budget.evictions() >= 2 && budget.reclaims() == 1, "Allocation run evicts and reclaims: evictions "
                + budget.evictions() + ", reclaims " + budget.reclaims());
        check(first.replayQueuedFrames() > 0 && first.replayLeases() >= 2 && !first.replayInProgress()
                && first.scheduleMisses() == 0, "Allocation run exercises the queue and a grant");
        return bytes;
    }

    /** Closed emitters keep their slot until a new renderer needs it; reusing a stale slot is not an eviction. */
    private static void staleSlotReused() {
        long[] clock = {0};
        var budget = clocked(2, 2, clock);
        float[] out = new float[2];
        for (int generation = 0; generation < 10; generation++) {
            var renderer = new LiveRenderer(budget);
            renderer.publish(joinAt(JOIN_AT, SignalDemo.graph()));
            for (int f = 0; f < 10; f++) renderer.render(out, 1, frameTime(f));
            clock[0] += ReplayBudget.STALE_NANOS + 1;
        }
        check(budget.evictions() == 0, "Replacing dead renderers never counts as an eviction, was " + budget.evictions());
        check(budget.reclaims() == 9 && budget.activeLeases() == 1, "Dead holders are reclaimed as stale: " + budget.reclaims());
        check(budget.grants() == 10 && budget.maxWaitFrames() == 0, "Budget-wide totals count every grant");
    }

    /** A sound engine reload replaces the sound thread after the old one exits. */
    private static void soundThreadHandoff() {
        long[] clock = {0};
        var budget = clocked(1, 2, clock);
        var old = new LiveRenderer(budget);
        old.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread soundThread = new Thread(() -> {
            try { old.render(new float[2], 1, JOIN_AT); } catch (Throwable t) { failure.set(t); }
        });
        soundThread.start();
        try { soundThread.join(); } catch (InterruptedException ex) { throw new AssertionError(ex); }
        check(failure.get() == null && budget.activeLeases() == 1, "Old sound thread took a lease");
        var fresh = new LiveRenderer(budget);
        fresh.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        fresh.render(out, 1, frameTime(1));
        check(budget.reclaims() == 1 && old.replayReclaims() == 1 && fresh.replayLeases() == 1,
                "The new sound thread takes over and the old thread's lease is reclaimed at once");
        for (int f = 2; f < RECOVERY_FRAMES + FADE_FRAMES + 10; f++) fresh.render(out, 1, frameTime(f));
        check(fresh.mixReady(), "Renderer on the new thread completes");
        drained(budget, fresh);
    }

    private static ReplayBudget clocked(int leases, int renderers, long[] clock) {
        return new ReplayBudget(leases, renderers, () -> clock[0]);
    }

    private static final long FRAME_NANOS = Math.round(1e9 / LiveRenderer.SAMPLE_RATE);
    private static final int STALE_FRAMES = (int) (ReplayBudget.STALE_NANOS / FRAME_NANOS) + 1;

    private static void staleHolderReclaimed() {
        long[] clock = {0};
        var budget = clocked(1, 2, clock);
        var holder = new LiveRenderer(budget);
        var waiter = new LiveRenderer(budget);
        holder.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        waiter.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        int f = 0, stoppedAt = 10, grantedAt = -1;
        for (; f < stoppedAt; f++) {
            clock[0] = f * FRAME_NANOS;
            holder.render(out, 1, frameTime(f)); waiter.render(out, 1, frameTime(f));
        }
        check(budget.activeLeases() == 1 && budget.queueDepth() == 1, "Holder replaying, waiter queued");
        for (; f < stoppedAt + STALE_FRAMES + 10 && grantedAt < 0; f++) {
            clock[0] = f * FRAME_NANOS;
            waiter.render(out, 1, frameTime(f));
            if (waiter.replayLeases() == 1) grantedAt = f;
        }
        check(grantedAt > 0 && grantedAt <= stoppedAt + STALE_FRAMES + 1,
                "Waiter granted within STALE_NANOS plus one render of the holder stopping, at frame " + grantedAt);
        check(budget.reclaims() == 1 && holder.replayReclaims() == 1 && waiter.replayReclaims() == 0, "The stopped holder was reclaimed");
        // The holder comes back: its generation is stale, so it requeues instead of replaying without a lease
        long maxWork = 0;
        for (int i = 0; i < 3 * RECOVERY_FRAMES; i++, f++) {
            clock[0] = f * FRAME_NANOS;
            long before = holder.historyFrames() + waiter.historyFrames();
            waiter.render(out, 1, frameTime(f)); holder.render(out, 1, frameTime(f));
            maxWork = Math.max(maxWork, holder.historyFrames() + waiter.historyFrames() - before);
        }
        check(maxWork <= LiveRenderer.REPLAY_PER_FRAME, "Returning holder never replays alongside the new holder");
        check(holder.mixReady() && waiter.mixReady() && holder.replayLeases() == 2, "Both complete after the reclaim");
        drained(budget, holder);
    }

    private static void staleWaiterRemoved() {
        long[] clock = {0};
        var budget = clocked(1, 3, clock);
        var holder = new LiveRenderer(budget);
        var stopped = new LiveRenderer(budget);
        var next = new LiveRenderer(budget);
        for (var renderer : new LiveRenderer[] {holder, stopped, next}) renderer.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        int f = 0;
        for (; f < 10; f++) {
            clock[0] = f * FRAME_NANOS;
            holder.render(out, 1, frameTime(f)); stopped.render(out, 1, frameTime(f)); next.render(out, 1, frameTime(f));
        }
        check(budget.queueDepth() == 2, "Two waiters queued");
        int reclaimedAt = -1;
        for (int i = 0; i < 3 * RECOVERY_FRAMES; i++, f++) {
            clock[0] = f * FRAME_NANOS;
            holder.render(out, 1, frameTime(f)); next.render(out, 1, frameTime(f));
            if (reclaimedAt < 0 && budget.reclaims() == 1) {
                reclaimedAt = f;
                check(budget.queueDepth() == 1, "The stopped waiter is removed from the queue");
            }
        }
        check(reclaimedAt > 0 && reclaimedAt <= 10 + STALE_FRAMES, "Stopped waiter reclaimed once stale, at frame " + reclaimedAt);
        check(stopped.replayReclaims() == 1 && stopped.replayLeases() == 0, "Stopped waiter never granted");
        check(next.replayLeases() == 1 && next.replayMaxWaitFrames() <= RECOVERY_FRAMES + 1 && next.mixReady(),
                "Next waiter granted as soon as the holder releases");
        drained(budget, next);
    }

    private static void reclaimWhileRendering() {
        long[] clock = {0};
        var budget = clocked(1, 2, clock);
        var holder = new LiveRenderer(budget);
        var waiter = new LiveRenderer(budget);
        holder.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        waiter.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        long maxWork = 0;
        for (int f = 0; f < 3 * RECOVERY_FRAMES; f++) {
            // One long gap on the local clock between reads, as if the sound thread stalled for the holder only
            clock[0] = f * FRAME_NANOS + (f >= 50 ? ReplayBudget.STALE_NANOS + FRAME_NANOS : 0);
            long before = holder.historyFrames() + waiter.historyFrames();
            if (f == 50) { waiter.render(out, 1, frameTime(f)); holder.render(out, 1, frameTime(f)); }
            else { holder.render(out, 1, frameTime(f)); waiter.render(out, 1, frameTime(f)); }
            long work = holder.historyFrames() + waiter.historyFrames() - before;
            check(work <= LiveRenderer.REPLAY_PER_FRAME, "Reclaimed holder stops replaying, work " + work + " at frame " + f);
            maxWork = Math.max(maxWork, work);
        }
        check(holder.replayReclaims() == 1 && holder.historyRecoveries() == 2, "Holder reclaimed mid-replay and restarted");
        check(holder.mixReady() && waiter.mixReady(), "Both complete after reclaim-while-active");
        drained(budget, holder);
    }

    private static void evictWhileRendering() {
        long[] clock = {0};
        var budget = clocked(1, 2, clock);
        var holder = new LiveRenderer(budget);
        var waiter = new LiveRenderer(budget);
        var extra = new LiveRenderer(budget);
        holder.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        waiter.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        extra.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        int f = 0;
        for (; f < 10; f++) {
            clock[0] = f;
            holder.render(out, 1, frameTime(f)); waiter.render(out, 1, frameTime(f));
        }
        // slots + 1 renderers: extra evicts the stalest slot, which is the holder's
        clock[0] = 20; extra.render(out, 1, frameTime(f));
        check(budget.evictions() == 1 && holder.replayEvictions() == 1, "Registering slots + 1 renderers evicts the stalest");
        clock[0] = 21; waiter.render(out, 1, frameTime(f));
        check(waiter.replayLeases() == 1, "Eviction frees the lease for the next waiter");
        clock[0] = 22; holder.render(out, 1, frameTime(f));
        check(budget.evictions() == 2 && extra.replayEvictions() == 1, "The evicted live renderer re-registers over the stalest slot");
        f++;
        long maxWork = 0;
        for (int i = 0; i < 3 * RECOVERY_FRAMES; i++, f++) {
            clock[0] = 22 + f;
            long before = holder.historyFrames() + waiter.historyFrames();
            waiter.render(out, 1, frameTime(f)); holder.render(out, 1, frameTime(f));
            maxWork = Math.max(maxWork, holder.historyFrames() + waiter.historyFrames() - before);
        }
        check(maxWork <= LiveRenderer.REPLAY_PER_FRAME, "Evicted holder never replays alongside the new holder");
        check(holder.mixReady() && waiter.mixReady() && holder.historyRecoveries() == 2, "Evicted renderer requeues and completes");
        drained(budget, holder);
    }

    private static void cancelRecoveryReleases() {
        var budget = new ReplayBudget(1, 2);
        var holder = new LiveRenderer(budget);
        var waiter = new LiveRenderer(budget);
        holder.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        waiter.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        int f = 0;
        for (; f < 10; f++) { holder.render(out, 1, frameTime(f)); waiter.render(out, 1, frameTime(f)); }
        waiter.cancelRecovery();
        check(budget.queueDepth() == 0, "Cancelling a waiter leaves the queue at once");
        holder.cancelRecovery();
        check(budget.activeLeases() == 0, "Cancelling a holder releases at once");
        long historyBefore = holder.historyFrames() + waiter.historyFrames();
        boolean silent = true;
        for (int i = 0; i < 2 * RECOVERY_FRAMES; i++, f++) {
            holder.render(out, 1, frameTime(f));
            silent &= out[0] == 0 && out[1] == 0;
            waiter.render(out, 1, frameTime(f));
            silent &= out[0] == 0 && out[1] == 0;
            check(budget.activeLeases() == 0 && budget.queueDepth() == 0, "Cancelled renderers never request again");
        }
        check(holder.historyFrames() + waiter.historyFrames() == historyBefore, "Cancelled renderers do no replay work");
        check(silent, "Cancelled unrecovered programs stay silent");
        check(holder.replayLeases() == 1 && waiter.replayLeases() == 0, "No new grants after cancel");
        drained(budget, holder);
    }

    private static void fastPaths() {
        // n = 0: a graph with nothing stateful never touches the budget
        var none = new ReplayBudget(1, 1);
        var plain = new LiveRenderer(none);
        Graph saw = new Graph(1, java.util.List.of(
                new Graph.Node("tone", NodeType.TONE, java.util.Map.of(NodeParam.WAVE, 1.0, NodeParam.FREQUENCY, 220.0, NodeParam.GAIN, 0.5)),
                new Graph.Node("out", NodeType.OUTPUT, java.util.Map.of())), java.util.List.of(Graph.edge("tone", "out")));
        plain.publish(joinAt(JOIN_AT, saw));
        float[] block = new float[4096];
        plain.render(block, 2048, JOIN_AT);
        check(plain.replayLeases() == 0 && none.activeLeases() == 0 && plain.mixReady(), "n = 0 never takes a lease");
        drained(none, plain);

        // n = 1 is oneRenderer; k >= n grants everything without waiting
        var wide = new ReplayBudget(4, 4);
        var renderers = new LiveRenderer[4];
        for (int r = 0; r < renderers.length; r++) { renderers[r] = new LiveRenderer(wide); renderers[r].publish(joinAt(JOIN_AT, SignalDemo.graph())); }
        float[] out = new float[2];
        for (int f = 0; f < RECOVERY_FRAMES + FADE_FRAMES + 10; f++) for (var renderer : renderers) renderer.render(out, 1, frameTime(f));
        for (var renderer : renderers)
            check(renderer.replayLeases() == 1 && renderer.replayQueuedFrames() == 0 && renderer.mixReady(), "k >= n never queues");
        drained(wide, renderers[0]);
    }

    private static void heartbeatIgnoresPlaybackTime() {
        long[] clock = {0};
        var budget = clocked(1, 3, clock);
        var ahead = new LiveRenderer(budget);
        var behind = new LiveRenderer(budget);
        ahead.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        behind.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        float[] out = new float[2];
        long lead = 30_000_000L;
        for (int f = 0; f < 4000; f++) {
            long base = frameTime(f);
            // Different stream leads, a resync to an earlier target, and a clock offset jump
            long aheadTime = base + lead + (f >= 2000 ? 5_000_000_000L : 0);
            long behindTime = f >= 1000 && f < 1100 ? base - 600_000_000L : base;
            ahead.render(out, 1, aheadTime);
            behind.render(out, 1, behindTime);
        }
        check(budget.reclaims() == 0 && budget.evictions() == 0, "Playback time never makes a renderer stale while the clock holds");
        check(ahead.resyncs() > 0 && behind.resyncs() > 0, "Both renderers saw playback jumps");
        // Both stop; with the clock still held, a newcomer's request reclaims nobody
        var late = new LiveRenderer(budget);
        late.publish(joinAt(JOIN_AT, SignalDemo.graph()));
        late.render(out, 1, frameTime(4000));
        check(budget.reclaims() == 0, "Stopped renderers are not stale until the local clock moves");
        clock[0] = ReplayBudget.STALE_NANOS + 1;
        late.render(out, 1, frameTime(4001));
        check(budget.reclaims() >= 1 && late.replayLeases() == 1, "Advancing the local clock past STALE_NANOS reclaims");
    }

    private static LiveRenderer.Timeline joinTimeline(SessionState pending) {
        SessionState state = new SessionState(1, 0, 0, 120, true, SignalDemo.graph());
        var timeline = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(state, pending));
        timeline.prepare(JOIN_AT);
        return timeline;
    }

    private static long frameTime(int frame) {
        return JOIN_AT + Math.round(frame * 1e9 / LiveRenderer.SAMPLE_RATE);
    }

    private static void drained(ReplayBudget budget, LiveRenderer renderer) {
        check(budget.activeLeases() == 0 && budget.queueDepth() == 0 && renderer.replayQueueDepth() == 0,
                "Active and queued counts return to 0");
        var probes = new ReplayBudget.Lease[budget.leases()];
        for (int i = 0; i < probes.length; i++) { probes[i] = new ReplayBudget.Lease(); budget.request(probes[i]); }
        check(Arrays.stream(probes).allMatch(l -> l.state == ReplayBudget.Lease.GRANTED), "All k leases are drainable");
        for (var probe : probes) budget.release(probe);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
}

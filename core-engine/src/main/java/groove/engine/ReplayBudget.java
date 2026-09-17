package groove.engine;

import java.util.function.LongSupplier;

/**
 * Replay leases shared by the LiveRenderers on one sound thread.
 * At most {@code leases} programs replay history at once; the rest wait in a FIFO queue.
 * Renderers heartbeat on a local clock; a renderer that stops rendering loses its leases.
 * Confined to one thread, preallocated, and allocation-free after construction.
 */
public final class ReplayBudget {
    public static final int DEFAULT_LEASES = 2;
    /** A renderer read about every 42.7 ms goes stale after about 6 missed reads. */
    public static final long STALE_NANOS = 250_000_000L;
    /** Current and pending of the observed timeline, plus current and pending of previous. */
    static final int PROGRAMS_PER_RENDERER = 4;
    private static final ReplayBudget UNLIMITED = new ReplayBudget();

    /** One per recovering top-level program, owned by its renderer. */
    static final class Lease {
        /** DROPPED: superseded by a newer publish before it was granted; holds nothing. */
        static final int IDLE = 0, QUEUED = 1, GRANTED = 2, DROPPED = 3;
        final LiveRenderer owner;
        int state = IDLE;
        long waitedFrames;
        /** Granted from the queue, possibly mid-frame after another program already replayed. */
        boolean waited;
        /** Owner's publish epoch when this program last requested. */
        long epoch;
        /** Budget-wide request order, for FIFO checks. */
        long requestSequence;
        /** Owner's slot when it requested; -1 for leases with no owner, which never go stale. */
        int slot = -1;

        Lease() { this(null); }
        Lease(LiveRenderer owner) { this.owner = owner; }

        boolean superseded() { return owner != null && owner.publishEpoch() > epoch; }
    }

    private final boolean unlimited;
    private final int leases;
    private final LongSupplier clock;
    private final Lease[] queue;
    private final Lease[] holders;
    private int head, size, active;
    private final LiveRenderer[] slotOwners;
    private final long[] slotSeen, slotGenerations;
    private final int[] slotLeases;
    private long requests;
    private volatile long reclaims, evictions, grants, maxWaitFrames;
    private Thread owner;
    private long[] grantLog;
    private int grantLogSize;

    private ReplayBudget() {
        unlimited = true; leases = Integer.MAX_VALUE; clock = null;
        queue = holders = new Lease[0];
        slotOwners = new LiveRenderer[0]; slotSeen = slotGenerations = new long[0]; slotLeases = new int[0];
    }

    /** A budget with the default lease count for up to {@code renderers} renderers. */
    public ReplayBudget(int renderers) { this(DEFAULT_LEASES, renderers); }

    public ReplayBudget(int leases, int renderers) { this(leases, renderers, System::nanoTime); }

    /** {@code clock} is a local monotonic clock in nanoseconds, never playback time. */
    public ReplayBudget(int leases, int renderers, LongSupplier clock) {
        if (leases < 1 || renderers < 1) throw new IllegalArgumentException("Leases and renderers must be positive");
        unlimited = false;
        this.leases = leases;
        this.clock = java.util.Objects.requireNonNull(clock);
        queue = new Lease[Math.multiplyExact(renderers, PROGRAMS_PER_RENDERER)];
        holders = new Lease[Math.min(leases, queue.length)];
        slotOwners = new LiveRenderer[renderers];
        slotSeen = new long[renderers];
        slotGenerations = new long[renderers];
        slotLeases = new int[renderers];
    }

    /** Grants every recovery immediately, matching renderers built without a budget. Keeps no state. */
    public static ReplayBudget unlimited() { return UNLIMITED; }

    public int leases() { return leases; }
    public int queueDepth() { return size; }
    public int activeLeases() { return active; }
    /** Renderers whose leases were taken back after they stopped rendering. Readable from any thread. */
    public long reclaims() { return reclaims; }
    /** Live renderers unregistered to make room for a new one. Readable from any thread. */
    public long evictions() { return evictions; }
    /** Leases granted so far. Readable from any thread. */
    public long grants() { return grants; }
    /** Longest wait in output frames of any granted lease. Readable from any thread. */
    public long maxWaitFrames() { return maxWaitFrames; }

    /** Tests only: records the request sequence of each grant, up to {@code capacity} grants. */
    void recordGrants(int capacity) { grantLog = new long[capacity]; grantLogSize = 0; }
    long[] grantLog() { return java.util.Arrays.copyOf(grantLog, grantLogSize); }

    /**
     * Start of every render: stamps the renderer's slot, registering it if needed.
     * Returns false when the renderer had a slot but lost it to a reclaim or eviction.
     */
    boolean heartbeat(LiveRenderer renderer) {
        if (unlimited) return true;
        confine();
        long now = clock.getAsLong();
        int slot = renderer.replaySlot;
        if (slot >= 0 && slotOwners[slot] == renderer && slotGenerations[slot] == renderer.replayGeneration) {
            slotSeen[slot] = now;
            return true;
        }
        register(renderer, now);
        return slot < 0;
    }

    /** Grants an idle lease if one is free, otherwise queues it. A superseded request is dropped. */
    void request(Lease lease) {
        if (lease.state != Lease.IDLE) throw new IllegalStateException("Lease already requested");
        lease.waitedFrames = 0;
        lease.waited = false;
        if (unlimited) { lease.state = Lease.GRANTED; return; }
        // Leases freed by a reclaim go to earlier waiters first
        decide();
        lease.requestSequence = requests++;
        if (lease.superseded()) { lease.state = Lease.DROPPED; return; }
        lease.slot = lease.owner == null ? -1 : lease.owner.replaySlot;
        if (lease.slot >= 0) slotLeases[lease.slot]++;
        // After every decision either all leases are taken or the queue is empty
        if (active < leases) { grant(lease); return; }
        if (size == queue.length) throw new IllegalStateException("Replay queue full: more requests than renderers allow");
        queue[(head + size++) % queue.length] = lease;
        lease.state = Lease.QUEUED;
    }

    /** Drops a queued, granted or dropped lease, then runs a grant decision. Idle leases are ignored. */
    void release(Lease lease) {
        if (lease.state == Lease.IDLE) return;
        // A thread handoff can vacate this lease, so confine before reading its state
        if (!unlimited) confine();
        int state = lease.state;
        if (state == Lease.IDLE) return;
        lease.state = Lease.IDLE;
        if (unlimited || state == Lease.DROPPED) return;
        if (lease.slot >= 0) slotLeases[lease.slot]--;
        if (state == Lease.GRANTED) removeHolder(lease);
        else remove(lease);
        decide();
    }

    /** Reclaims stale renderers, then grants queue heads in order while leases are free. */
    void decide() {
        if (unlimited) return;
        confine();
        reclaimStale();
        while (active < leases && size > 0) {
            Lease next = queue[head];
            queue[head] = null;
            head = (head + 1) % queue.length;
            size--;
            if (next.superseded()) {
                next.state = Lease.DROPPED;
                if (next.slot >= 0) slotLeases[next.slot]--;
                continue;
            }
            grant(next);
            next.waited = true;
        }
    }

    private void grant(Lease lease) {
        lease.state = Lease.GRANTED;
        holders[active++] = lease;
        grants++;
        if (lease.waitedFrames > maxWaitFrames) maxWaitFrames = lease.waitedFrames;
        if (grantLog != null && grantLogSize < grantLog.length) grantLog[grantLogSize++] = lease.requestSequence;
    }

    /** Only slots with leases are reclaimed, so a paused renderer with nothing to replay keeps its slot. */
    private void reclaimStale() {
        long now = clock.getAsLong();
        for (int slot = 0; slot < slotOwners.length; slot++) {
            if (slotOwners[slot] == null || slotLeases[slot] == 0 || now - slotSeen[slot] <= STALE_NANOS) continue;
            LiveRenderer stale = slotOwners[slot];
            vacate(slot);
            reclaims++;
            stale.countReplayReclaim();
        }
    }

    private void register(LiveRenderer renderer, long now) {
        int slot = -1;
        for (int i = 0; i < slotOwners.length && slot < 0; i++) if (slotOwners[i] == null) slot = i;
        if (slot < 0) {
            slot = 0;
            for (int i = 1; i < slotOwners.length; i++) if (slotSeen[i] < slotSeen[slot]) slot = i;
            LiveRenderer previous = slotOwners[slot];
            boolean held = slotLeases[slot] > 0;
            vacate(slot);
            // A stale slot is reused quietly; only taking a live renderer's slot is an eviction
            if (now - slotSeen[slot] > STALE_NANOS) {
                if (held) { reclaims++; previous.countReplayReclaim(); }
            } else {
                evictions++;
                previous.countReplayEviction();
            }
        }
        slotOwners[slot] = renderer;
        slotSeen[slot] = now;
        renderer.replaySlot = slot;
        renderer.replayGeneration = slotGenerations[slot];
        // Leases freed by an eviction go to the next waiters
        decide();
    }

    /** Frees a slot and every lease on it; its old owner sees the new generation on its next render. */
    private void vacate(int slot) {
        slotOwners[slot] = null;
        slotGenerations[slot]++;
        if (slotLeases[slot] == 0) return;
        for (int i = active - 1; i >= 0; i--) {
            if (holders[i].slot != slot) continue;
            holders[i].state = Lease.IDLE;
            holders[i] = holders[--active];
            holders[active] = null;
        }
        int kept = 0;
        for (int i = 0; i < size; i++) {
            Lease lease = queue[(head + i) % queue.length];
            if (lease.slot == slot) lease.state = Lease.IDLE;
            else queue[(head + kept++) % queue.length] = lease;
        }
        for (int i = kept; i < size; i++) queue[(head + i) % queue.length] = null;
        size = kept;
        slotLeases[slot] = 0;
    }

    private void removeHolder(Lease lease) {
        for (int i = 0; i < active; i++) {
            if (holders[i] != lease) continue;
            holders[i] = holders[--active];
            holders[active] = null;
            return;
        }
        throw new IllegalStateException("Granted lease missing from holders");
    }

    private void remove(Lease lease) {
        int at = 0;
        while (at < size && queue[(head + at) % queue.length] != lease) at++;
        if (at == size) throw new IllegalStateException("Queued lease missing from replay queue");
        for (; at < size - 1; at++) queue[(head + at) % queue.length] = queue[(head + at + 1) % queue.length];
        queue[(head + --size) % queue.length] = null;
    }

    /** Minecraft replaces its sound thread on a sound engine reload, after the old one exits. */
    private void confine() {
        Thread current = Thread.currentThread();
        if (owner == current) return;
        if (owner != null && owner.isAlive()) throw new IllegalStateException("Replay budget used from a second thread");
        boolean handoff = owner != null;
        owner = current;
        if (!handoff) return;
        // Renderers of the old thread start over if they render again
        for (int slot = 0; slot < slotOwners.length; slot++) {
            LiveRenderer previous = slotOwners[slot];
            if (previous == null) continue;
            boolean held = slotLeases[slot] > 0;
            vacate(slot);
            if (held) { reclaims++; previous.countReplayReclaim(); }
        }
    }
}

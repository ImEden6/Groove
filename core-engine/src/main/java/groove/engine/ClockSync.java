package groove.engine;

/** Low-RTT midpoint estimate. Audio rendering slews toward this estimate independently. */
public final class ClockSync {
    private long bestRtt = Long.MAX_VALUE;
    private volatile double offset;
    private volatile boolean ready;
    public boolean observe(long sent, long received, long serverNanos) {
        long rtt = received - sent;
        if (rtt < 0 || rtt > 2_000_000_000L) return false;
        // Permit slowly changing network conditions without trusting arbitrary slow samples.
        if (ready && rtt > bestRtt + 20_000_000L) {
            bestRtt = Math.min(2_000_000_000L, bestRtt + 1_000_000L);
            return false;
        }
        bestRtt = Math.min(bestRtt, rtt);
        double measured = serverNanos - (sent + rtt / 2.0);
        offset = ready ? offset + Math.max(-1_000_000, Math.min(1_000_000, measured - offset)) : measured;
        ready = true;
        return true;
    }
    public boolean ready() { return ready; }
    public long serverTime(long localNanos) { return localNanos + Math.round(offset); }
}

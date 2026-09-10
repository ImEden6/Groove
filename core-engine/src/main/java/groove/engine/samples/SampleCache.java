package groove.engine.samples;

import java.util.LinkedHashMap;

/** Thread-safe LRU; evicting a cache entry never invalidates PCM already pinned by a program. */
public final class SampleCache {
    private final long capacity;
    private long bytes;
    private final LinkedHashMap<String, SampleData> entries = new LinkedHashMap<>(16, .75f, true);
    public SampleCache(long capacity) { if (capacity < 1) throw new IllegalArgumentException("Invalid cache size"); this.capacity = capacity; }
    public synchronized SampleData get(String hash) { return entries.get(hash); }
    public synchronized long bytes() { return bytes; }
    public synchronized void put(String hash, SampleData sample) {
        if (sample.bytes() > capacity) throw new IllegalArgumentException("Sample exceeds cache budget");
        SampleData old = entries.remove(hash);
        if (old != null) bytes -= old.bytes();
        while (bytes + sample.bytes() > capacity) {
            var oldest = entries.entrySet().iterator();
            bytes -= oldest.next().getValue().bytes(); oldest.remove();
        }
        entries.put(hash, sample); bytes += sample.bytes();
    }
}

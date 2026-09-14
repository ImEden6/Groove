package groove.engine.samples;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable encoded catalog snapshots; scanning and hashing are control-thread work. */
public final class SampleCatalog {
    public static final int MAX_ASSETS = 256, MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    public enum Status { READY, MISSING, HASH_MISMATCH }
    public static final class Entry {
        private final AssetRef ref;
        private final byte[] data;
        private Entry(String id, byte[] data) { ref = new AssetRef(id, AssetRef.hash(data)); this.data = data; }
        public AssetRef ref() { return ref; }
        public int size() { return data.length; }
        public byte[] bytes() { return data.clone(); }
        public byte[] chunk(int offset, int size) {
            if (offset < 0 || offset >= data.length || size < 1) throw new IllegalArgumentException("Invalid chunk");
            return Arrays.copyOfRange(data, offset, Math.min(data.length, offset + size));
        }
    }
    private final Map<String, Entry> entries;
    private final List<String> warnings;
    private final Map<AssetRef, Entry> exactEntries;
    private SampleCatalog(Map<String, Entry> entries, List<String> warnings) {
        this(entries, warnings, entries.values().stream().collect(java.util.stream.Collectors.toMap(Entry::ref, e -> e)));
    }
    private SampleCatalog(Map<String, Entry> entries, List<String> warnings, Map<AssetRef, Entry> exactEntries) {
        this.entries = Map.copyOf(entries); this.warnings = List.copyOf(warnings);
        this.exactEntries = Map.copyOf(exactEntries);
    }
    public List<Entry> entries() { return entries.values().stream().sorted(Comparator.comparing(e -> e.ref.assetId())).toList(); }
    public List<String> warnings() { return warnings; }
    public Entry find(String id) { return entries.get(id); }
    public Set<AssetRef> references() { return exactEntries.keySet(); }
    public Entry find(AssetRef ref) { return exactEntries.get(ref); }
    /** Local IDs win browsing collisions; exact hashes from both bounded catalogs remain resolvable. */
    public static SampleCatalog withDownloads(SampleCatalog local, SampleCatalog downloads) {
        Map<String, Entry> entries = new HashMap<>(downloads.entries);
        entries.putAll(local.entries);
        Map<AssetRef, Entry> exact = new HashMap<>(downloads.exactEntries);
        exact.putAll(local.exactEntries);
        List<String> warnings = new ArrayList<>(local.warnings);
        for (String warning : downloads.warnings) warnings.add("Download cache: " + warning);
        return new SampleCatalog(entries, warnings, exact);
    }
    public Status status(AssetRef ref) {
        if (exactEntries.containsKey(ref)) return Status.READY;
        Entry entry = find(ref.assetId());
        return entry == null ? Status.MISSING : entry.ref.equals(ref) ? Status.READY : Status.HASH_MISMATCH;
    }
    /**
     * Resolves where a {@code custom:} asset id would live under a samples root, for installing a
     * file that does not exist yet. {@link AssetRef}'s own validation still allows a leading '/' in
     * the id (e.g. "custom:/etc/passwd"), and {@link Path#resolve} silently discards the base for an
     * absolute-looking input, so this performs its own normalize+startsWith sandbox check rather than
     * relying on {@link #scan}'s escape guard, which only applies to paths of files that already exist.
     */
    public static Path resolveCustomPath(Path root, AssetRef ref) {
        String id = ref.assetId();
        if (!id.startsWith("custom:")) throw new IllegalArgumentException("Not an installable custom asset: " + id);
        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(id.substring("custom:".length())).normalize();
        if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("Asset path escapes samples root: " + id);
        return resolved;
    }
    public static SampleCatalog scan(Path root) throws IOException {
        Map<String, Entry> entries = new HashMap<>(); List<String> warnings = new ArrayList<>();
        long total = 0;
        for (String id : FactorySamples.ids()) {
            Entry e = new Entry(id, FactorySamples.bytes(id)); entries.put(id, e); total += e.size();
        }
        if (root == null || !Files.exists(root)) return new SampleCatalog(entries, warnings);
        Path realRoot = root.toRealPath();
        try (var walk = Files.walk(realRoot, 8)) {
            Iterator<Path> paths = walk.iterator(); int visited = 0;
            while (paths.hasNext()) {
                if (++visited > 4096) { warnings.add("Scan stopped at 4096 filesystem entries"); break; }
                Path path = paths.next();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = realRoot.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".wav") && !relative.endsWith(".ogg")) continue;
                try {
                    if (!path.toRealPath().startsWith(realRoot)) throw new IOException("File escapes pack root");
                    if (entries.size() >= MAX_ASSETS) throw new IOException("Catalog asset limit reached");
                    if (Files.size(path) > SampleData.MAX_BYTES) throw new IOException("Encoded file exceeds 4 MiB");
                    byte[] data;
                    try (var input = Files.newInputStream(path)) { data = input.readNBytes(SampleData.MAX_BYTES + 1); }
                    if (data.length > SampleData.MAX_BYTES || total + data.length > MAX_TOTAL_BYTES) throw new IOException("Catalog byte limit reached");
                    Entry e = new Entry("custom:" + relative, data);
                    entries.put(e.ref.assetId(), e); total += e.size();
                } catch (IOException | IllegalArgumentException error) { warnings.add(relative + ": " + error.getMessage()); }
            }
        }
        return new SampleCatalog(entries, warnings);
    }
}

package groove.engine.samples;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/**
 * Decides which installed custom sample files to remove so a newly installed one fits the shared
 * client-side catalog budget ({@link SampleCatalog#MAX_ASSETS}/{@link SampleCatalog#MAX_TOTAL_BYTES}).
 * Eviction order is oldest file-system last-modified time first, not true usage-based LRU. A real
 * LRU would need to touch file metadata from {@code resolve()}, which runs on several different
 * threads including UI painting, not just the dedicated sample IO executor.
 */
public final class SampleEviction {
    public record Installed(String assetId, Path path, long size, FileTime installedAt) {}

    /** Reads existing installed custom files' sizes and install times; does not open/hash their content. */
    public static List<Installed> scan(Path root) throws IOException {
        List<Installed> installed = new ArrayList<>();
        if (root == null || !Files.exists(root)) return installed;
        SampleInstallStore.requireUnlinked(root);
        Path realRoot = root.toRealPath();
        try (var walk = Files.walk(realRoot, 8)) {
            Iterator<Path> paths = walk.iterator(); int visited = 0;
            while (paths.hasNext()) {
                if (++visited > 4096) throw new IOException("Install scan exceeds 4096 entries");
                Path path = paths.next();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                SampleInstallStore.requireUnlinked(path);
                String relative = realRoot.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".wav") && !relative.endsWith(".ogg")) continue;
                try {
                    installed.add(new Installed("custom:" + relative, path, Files.size(path), Files.getLastModifiedTime(path)));
                } catch (IOException ignored) { /* file changed/removed mid-scan; skip it */ }
            }
        }
        return installed;
    }

    /**
     * Picks victims (oldest install time first, skipping {@code protectedAssetIds}) so that
     * {@code incomingSize} fits under {@code byteBudget}/{@code countBudget} alongside the rest.
     * Throws {@link IllegalArgumentException} if it cannot fit even after evicting every eligible file.
     */
    public static List<Installed> select(List<Installed> installed, Set<String> protectedAssetIds,
            long incomingSize, long byteBudget, int countBudget) {
        return select(installed, protectedAssetIds, null, incomingSize, byteBudget, countBudget);
    }

    /** Replacing an ID consumes only its new size and does not add another catalog entry. */
    public static List<Installed> select(List<Installed> installed, Set<String> protectedAssetIds,
            String incomingId, long incomingSize, long byteBudget, int countBudget) {
        installed = installed.stream().filter(entry -> !entry.assetId().equals(incomingId)).toList();
        if (incomingSize < 0 || byteBudget < 0 || countBudget < 1) throw new IllegalArgumentException("Invalid storage budget");
        if (incomingSize > byteBudget) throw new IllegalArgumentException("Incoming asset exceeds storage budget");
        long totalBytes = incomingSize;
        for (Installed entry : installed) totalBytes += entry.size();
        int totalCount = installed.size() + 1;
        List<Installed> candidates = new ArrayList<>();
        for (Installed entry : installed) if (!protectedAssetIds.contains(entry.assetId())) candidates.add(entry);
        candidates.sort(Comparator.comparingLong(entry -> entry.installedAt().toMillis()));
        List<Installed> evicted = new ArrayList<>();
        int index = 0;
        while ((totalBytes > byteBudget || totalCount > countBudget) && index < candidates.size()) {
            Installed victim = candidates.get(index++);
            evicted.add(victim);
            totalBytes -= victim.size();
            totalCount--;
        }
        if (totalBytes > byteBudget || totalCount > countBudget)
            throw new IllegalArgumentException("Cannot fit incoming asset even after evicting every eligible file");
        return evicted;
    }
}

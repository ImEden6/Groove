package groove.engine.samples;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.function.Supplier;

/** Owned download folder only; never point this store at the user's sample-pack folder. */
public final class SampleInstallStore {
    private final Path root;
    private final long byteBudget;
    private final int countBudget;

    public SampleInstallStore(Path root, long byteBudget, int countBudget) {
        this.root = root.toAbsolutePath().normalize();
        this.byteBudget = byteBudget;
        this.countBudget = countBudget;
    }

    @FunctionalInterface private interface DiskAction { void run() throws IOException; }

    /** Cancellation and final disk mutations share a lock. Decoding/staging do not hold it. */
    public static final class Session {
        private volatile boolean cancelled;
        public synchronized void cancel() { cancelled = true; }
        public boolean cancelled() { return cancelled; }
        private synchronized boolean commit(DiskAction action) throws IOException {
            if (cancelled) return false;
            action.run();
            return true;
        }
    }

    /** Reject symlinks and Windows junctions, including links in existing ancestor directories. */
    public static void requireUnlinked(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            BasicFileAttributes attrs;
            try { attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException absent) { continue; }
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Linked install path is not allowed: " + current);
        }
    }

    private void pruneEmptyParents(Path parent) throws IOException {
        while (parent != null && !parent.equals(root) && parent.startsWith(root)) {
            requireUnlinked(parent);
            try { Files.delete(parent); }
            catch (DirectoryNotEmptyException occupied) { return; }
            catch (NoSuchFileException absent) { /* Already removed by another victim. */ }
            parent = parent.getParent();
        }
    }

    /** Caller has decoded the audio. Returns false when the originating connection was cancelled. */
    public synchronized boolean install(AssetRef ref, byte[] data, Session session,
                                       Supplier<Set<String>> protectedIds) throws IOException {
        if (session.cancelled()) return false;
        Path target = SampleCatalog.resolveCustomPath(root, ref);
        if (!(ref.assetId().endsWith(".wav") || ref.assetId().endsWith(".ogg"))
                || root.relativize(target).getNameCount() > 8
                || data.length == 0 || data.length > SampleData.MAX_BYTES
                || !AssetRef.hash(data).equals(ref.sha256()))
            throw new IllegalArgumentException("Invalid install asset");
        requireUnlinked(target);
        // Temporary data stays in the managed root, never beside a user-supplied linked target.
        Path[] staged = new Path[1];
        if (!session.commit(() -> {
            requireUnlinked(root);
            Files.createDirectories(root);
            requireUnlinked(root);
            staged[0] = Files.createTempFile(root, "download-", ".tmp");
        })) return false;
        Path temp = staged[0];
        try {
            Files.write(temp, data, LinkOption.NOFOLLOW_LINKS);
            var installed = SampleEviction.scan(root);
            return session.commit(() -> {
                requireUnlinked(target);
                var victims = SampleEviction.select(installed, protectedIds.get(), ref.assetId(),
                        data.length, byteBudget, countBudget);
                for (var victim : victims) {
                    if (!victim.path().toAbsolutePath().normalize().startsWith(root))
                        throw new IOException("Eviction path escapes managed root");
                    requireUnlinked(victim.path());
                }
                Files.createDirectories(target.getParent());
                requireUnlinked(target);
                requireUnlinked(temp);
                // Do not delete anything if staging or the atomic replacement fails.
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                for (var victim : victims) {
                    requireUnlinked(victim.path());
                    Files.deleteIfExists(victim.path());
                    pruneEmptyParents(victim.path().getParent());
                }
            });
        } finally {
            requireUnlinked(temp);
            Files.deleteIfExists(temp);
        }
    }
}

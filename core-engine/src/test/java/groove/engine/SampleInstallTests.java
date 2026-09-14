package groove.engine;

import groove.engine.samples.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual filesystem transactions and request lifecycle regressions, without Fabric. */
public final class SampleInstallTests {
    private static int checks;
    public static void run() throws Exception {
        Path workspace = Files.createTempDirectory("groove-install-tests-");
        try {
            byte[] first = FactorySamples.bytes("factory:basic/kick.wav");
            byte[] second = FactorySamples.bytes("factory:basic/snare.wav");
            AssetRef a = new AssetRef("custom:kit/a.wav", AssetRef.hash(first));
            AssetRef b = new AssetRef("custom:kit/b.wav", AssetRef.hash(second));
            Path local = workspace.resolve("samples"), managed = workspace.resolve("downloaded-samples");
            Path user = local.resolve("kit/a.wav");
            Files.createDirectories(user.getParent()); Files.write(user, second);
            var store = new SampleInstallStore(managed, first.length + second.length, 1);
            var session = new SampleInstallStore.Session();
            check(store.install(a, first, session, Set::of), "First download installs");
            check(Arrays.equals(Files.readAllBytes(user), second), "Download collision preserves user's original bytes");
            var combined = SampleCatalog.withDownloads(SampleCatalog.scan(local), SampleCatalog.scan(managed));
            AssetRef own = new AssetRef(a.assetId(), AssetRef.hash(second));
            check(combined.find(a.assetId()).ref().equals(own), "User sample wins browsing collision");
            check(combined.status(a) == SampleCatalog.Status.READY && combined.status(own) == SampleCatalog.Status.READY,
                    "Both exact hashes resolve despite the shared ID");
            check(Arrays.equals(combined.find(a).bytes(), first), "Graph lookup gets exact downloaded bytes");
            check(store.install(a, first, session, () -> Set.of(a.assetId())), "Reinstall at full protected count needs no extra slot");
            AssetRef replacement = new AssetRef(a.assetId(), AssetRef.hash(second));
            check(store.install(replacement, second, session, () -> Set.of(a.assetId())), "Replacement accounts only for new bytes");
            check(store.install(b, second, session, Set::of), "New download evicts an old managed entry");
            check(!Files.exists(managed.resolve("kit/a.wav")) && Files.exists(user), "Eviction touches only managed files");
            check(Arrays.equals(Files.readAllBytes(user), second), "Eviction never rewrites user files");

            // A failed rename must leave the proposed eviction victim intact.
            AssetRef blocked = new AssetRef("custom:blocked.wav", a.sha256());
            Files.createDirectories(managed.resolve("blocked.wav"));
            Files.writeString(managed.resolve("blocked.wav/keep.txt"), "keep");
            rejected(() -> store.install(blocked, first, session, Set::of));
            check(Files.exists(managed.resolve("kit/b.wav")), "Failed replacement preserves victims");

            // Simulate a decoder finishing after disconnect and after the next session starts.
            CountDownLatch decoding = new CountDownLatch(1), resume = new CountDownLatch(1);
            var oldSession = new SampleInstallStore.Session();
            var worker = Executors.newSingleThreadExecutor();
            AtomicBoolean protectionRead = new AtomicBoolean();
            try {
                var late = worker.submit(() -> {
                    decoding.countDown();
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Decoder never resumed");
                    return store.install(a, first, oldSession, () -> { protectionRead.set(true); return Set.of(); });
                });
                check(decoding.await(5, TimeUnit.SECONDS), "Decoder is pending");
                oldSession.cancel();
                var newSession = new SampleInstallStore.Session();
                resume.countDown();
                check(!late.get(5, TimeUnit.SECONDS), "Disconnected transfer is discarded");
                check(!protectionRead.get() && Files.exists(managed.resolve("kit/b.wav")), "Stale transfer cannot read new protections or evict");
                check(store.install(a, first, newSession, Set::of), "New connection can still install");
            } finally { resume.countDown(); worker.shutdownNow(); }

            Path outside = workspace.resolve("outside"); Files.createDirectories(outside);
            Path link = managed.resolve("linked");
            createDirectoryLink(link, outside);
            try {
                AssetRef escaped = new AssetRef("custom:linked/escape.wav", a.sha256());
                rejected(() -> store.install(escaped, first, session, Set::of));
                check(!Files.exists(outside.resolve("escape.wav")), "Linked parent cannot redirect a write");
                var linkedRoot = new SampleInstallStore(link, first.length, 1);
                rejected(() -> linkedRoot.install(a, first, session, Set::of));
                try (var files = Files.list(outside)) { check(files.findAny().isEmpty(), "Linked root remains untouched"); }
            } finally { Files.deleteIfExists(link); }

            var installed = List.of(new SampleEviction.Installed("custom:x.wav", managed.resolve("x.wav"), 8,
                    java.nio.file.attribute.FileTime.fromMillis(1)));
            check(SampleEviction.select(installed, Set.of("custom:x.wav"), "custom:x.wav", 10, 10, 1).isEmpty(),
                    "Growing replacement fits the exact byte budget");
            rejected(() -> SampleEviction.select(installed, Set.of("custom:x.wav"), "custom:x.wav", 11, 10, 1));
            requestChecks(a, b, blocked);
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Passed " + checks + " sample installation/lifecycle checks.");
    }
    private static void requestChecks(AssetRef a, AssetRef b, AssetRef c) {
        SampleRequestQueue queue = new SampleRequestQueue();
        queue.add(a, true); queue.add(b, true); queue.add(c, false);
        check(queue.poll().equals(new SampleRequestQueue.Request(a, true)), "First explicit install starts");
        queue.retainGraph(List.of()); // The completed install triggers a graph refresh with no missing assets.
        check(queue.poll().equals(new SampleRequestQueue.Request(b, true)), "Refresh preserves next explicit install");
        check(queue.poll() == null, "Refresh removes obsolete automatic fetch");
        queue.add(a, false); queue.add(a, true); queue.retainGraph(List.of());
        check(queue.poll().install() && queue.poll() == null, "Promotion deduplicates and preserves explicit intent");
        for (int i=0;i<128;i++) check(queue.add(new AssetRef("custom:"+i+".wav", a.sha256()), false), "Queue accepts bounded requests");
        check(!queue.add(b, true), "Queue rejects overflow");
        queue.clear(); check(queue.poll() == null, "Disconnect clears queue");
    }
    private static void createDirectoryLink(Path link, Path destination) throws Exception {
        try { Files.createSymbolicLink(link, destination); }
        catch (IOException | UnsupportedOperationException error) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw error;
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), destination.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) throw new IOException("Cannot create test junction: " + output, error);
        }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected install rejection");
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}

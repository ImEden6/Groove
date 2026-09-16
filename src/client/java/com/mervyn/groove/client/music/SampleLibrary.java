package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.music.MusicPackets;
import groove.engine.*;
import groove.engine.samples.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Catalog/decoder service. No filesystem or decoding calls are made by the audio thread. */
public final class SampleLibrary {
    public record Prepared(LiveRenderer.Timeline timeline, Map<AssetRef, String> status, List<AssetRef> needed) {}
    private static volatile SampleCatalog catalog;
    private static final SampleCache CACHE = new SampleCache(64L * 1024 * 1024);
    private static final LinkedHashMap<AssetRef, byte[]> REMOTE = new LinkedHashMap<>(16, .75f, true);
    private static long remoteBytes;
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "Groove sample IO"); t.setDaemon(true); return t;
    });
    private static final SampleRequestQueue queued = new SampleRequestQueue();
    private static SampleInstallStore.Session installSession = new SampleInstallStore.Session();
    private static final Map<AssetRef, Long> failed = new HashMap<>();
    private static AssetRef flight;
    private static boolean flightIsInstall;
    private static SampleTransfer transfer;
    private static long sentAt, session, nextSendAt;
    private static int retries;
    private static boolean decoding;
    private static volatile Map<AssetRef, String> errors = Map.of();
    private static volatile Set<AssetRef> remoteAvailable = Set.of();
    private static volatile Set<AssetRef> graphReferenced = Set.of();
    static {
        try { catalog = SampleCatalog.scan(null); } catch (java.io.IOException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
    public static SampleCatalog catalog() { return catalog; }
    public static Executor executor() { return IO; }
    public static Map<AssetRef, String> errors() { return errors; }
    public static Set<AssetRef> remoteAvailable() { return remoteAvailable; }
    public static void register() {
        IO.scheduleWithFixedDelay(SampleLibrary::reload, 0, 5, TimeUnit.SECONDS);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> IO.shutdownNow());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            speakerAssets.clear();
            globalNeeded = List.of(); previewNeeded = List.of(); globalRefs = Set.of(); previewRefs = Set.of();
            installSession.cancel(); installSession = new SampleInstallStore.Session();
            session++; queued.clear(); failed.clear(); errors = Map.of();
            flight = null; flightIsInstall = false; transfer = null; decoding = false;
            remoteAvailable = Set.of(); graphReferenced = Set.of();
            synchronized (REMOTE) { REMOTE.clear(); remoteBytes = 0; }
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.CatalogSnapshot.TYPE, (packet, context) -> {
            var available = Set.copyOf(packet.assets());
            remoteAvailable = available;
            SampleCatalog local = catalog;
            long missing = available.stream().filter(ref -> local.status(ref) != SampleCatalog.Status.READY).count();
            if (missing > 0 && context.client().player != null)
                context.client().player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Groove: server has " + missing + " custom sample(s) not yet installed. "
                                + "Run /groove-samples list to browse, /groove-samples install <id> to fetch."), false);
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.AssetChunk.TYPE, (packet, context) -> {
            if (decoding || !packet.ref().equals(flight)) return;
            if (packet.total() == 0) { fail("SERVER_ASSET_UNAVAILABLE"); return; }
            if (packet.offset() != (transfer == null ? 0 : transfer.offset())) return;
            try {
                if (transfer == null) transfer = new SampleTransfer(flight, packet.total());
                if (packet.total() != transfer.total()) throw new IllegalArgumentException("Transfer length changed between chunks");
                transfer.append(packet.offset(), packet.data());
                sentAt = 0; retries = 0; nextSendAt = System.nanoTime() + 50_000_000L;
                if (transfer.complete()) {
                    completeTransfer(transfer, flight, session, context.client());
                }
            } catch (IllegalArgumentException error) { fail("INVALID_TRANSFER: " + error.getMessage()); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getConnection() == null || decoding) return;
            if (flight == null) {
                var next = queued.poll();
                if (next == null) return;
                flight = next.ref(); flightIsInstall = next.install();
                transfer = null; retries = 0; sentAt = 0;
            }
            if (flight == null) return;
            var type = flightIsInstall ? MusicPackets.AssetInstallRequest.TYPE : MusicPackets.AssetRequest.TYPE;
            if (!ClientPlayNetworking.canSend(type)) { fail("SERVER_TRANSFER_UNSUPPORTED"); return; }
            long now = System.nanoTime();
            if (now >= nextSendAt && (sentAt == 0 || now - sentAt > 2_000_000_000L)) {
                if (++retries > 3) { fail("TRANSFER_TIMEOUT"); return; }
                int offset = transfer == null ? 0 : transfer.offset();
                ClientPlayNetworking.send(flightIsInstall ? new MusicPackets.AssetInstallRequest(flight, offset) : new MusicPackets.AssetRequest(flight, offset));
                sentAt = now;
            }
        });
    }
    private static java.nio.file.Path samplesRoot() { return FabricLoader.getInstance().getGameDir().resolve("groove/samples"); }
    private static java.nio.file.Path downloadsRoot() { return FabricLoader.getInstance().getGameDir().resolve("groove/downloaded-samples"); }
    public static void reload() {
        try {
            var root = samplesRoot();
            Files.createDirectories(root);
            SampleCatalog local = SampleCatalog.scan(root);
            SampleCatalog downloads;
            try {
                SampleInstallStore.requireUnlinked(downloadsRoot());
                downloads = SampleCatalog.scan(downloadsRoot());
            } catch (java.io.IOException error) {
                GrooveMod.LOGGER.warn("Could not scan managed sample downloads", error);
                downloads = SampleCatalog.scan(null);
            }
            SampleCatalog next = SampleCatalog.withDownloads(local, downloads);
            boolean changed = !next.references().equals(catalog.references());
            catalog = next;
            if (changed) Minecraft.getInstance().execute(MusicClient::refreshSamples);
        } catch (Exception error) { GrooveMod.LOGGER.warn("Could not scan client sample packs", error); }
    }
    public static SampleData resolve(AssetRef ref) {
        SampleData hit = CACHE.get(ref.sha256());
        if (hit != null) return hit;
        byte[] data = null;
        SampleCatalog current = catalog;
        if (current.status(ref) == SampleCatalog.Status.READY) data = current.find(ref).bytes();
        if (data == null) synchronized (REMOTE) { data = REMOTE.get(ref); }
        if (data == null) throw new IllegalArgumentException(current.status(ref).name());
        if (!AssetRef.hash(data).equals(ref.sha256())) throw new IllegalArgumentException("HASH_MISMATCH");
        SampleData decoded = SampleDecoder.decode(data); CACHE.put(ref.sha256(), decoded); return decoded;
    }
    public static Prepared prepare(SessionTimeline.Snapshot snapshot) {
        var bare = LiveRenderer.Timeline.compile(snapshot);
        Set<AssetRef> refs = new LinkedHashSet<>();
        for (Graph.Node node : snapshot.current().graph().nodes()) if (node.sample() != null) refs.add(node.sample());
        if (snapshot.pending() != null) for (Graph.Node node : snapshot.pending().graph().nodes()) if (node.sample() != null) refs.add(node.sample());
        Map<AssetRef, SampleData> bank = new HashMap<>(); Map<AssetRef, String> status = new LinkedHashMap<>();
        List<AssetRef> needed = new ArrayList<>(); Set<String> counted = new HashSet<>(); long pinned = 0;
        for (AssetRef ref : refs) {
            try {
                SampleData data = resolve(ref);
                if (counted.add(ref.sha256())) pinned += data.bytes();
                if (pinned > 32L * 1024 * 1024) throw new IllegalArgumentException("ACTIVE_SAMPLE_BUDGET_EXCEEDED");
                bank.put(ref, data); status.put(ref, "READY");
            } catch (IllegalArgumentException error) {
                status.put(ref, error.getMessage());
                if ("MISSING".equals(error.getMessage()) || "HASH_MISMATCH".equals(error.getMessage())) needed.add(ref);
            }
        }
        return new Prepared(LiveRenderer.Timeline.withSamples(bare, bank), Map.copyOf(status), List.copyOf(needed));
    }
    private record SpeakerAssets(List<AssetRef> needed, Set<AssetRef> refs) {}
    private static final Map<net.minecraft.core.BlockPos, SpeakerAssets> speakerAssets = new HashMap<>();
    public static void requestSpeaker(net.minecraft.core.BlockPos pos, List<AssetRef> needed, Set<AssetRef> refs) {
        speakerAssets.put(pos, new SpeakerAssets(List.copyOf(needed), Set.copyOf(refs))); updateRequests();
    }
    public static void removeSpeaker(net.minecraft.core.BlockPos pos) {
        if (speakerAssets.remove(pos) != null) updateRequests();
    }
    private static List<AssetRef> globalNeeded = List.of(), previewNeeded = List.of();
    private static Set<AssetRef> globalRefs = Set.of(), previewRefs = Set.of();
    public static void request(List<AssetRef> needed, Set<AssetRef> referenced) {
        globalNeeded = List.copyOf(needed); globalRefs = Set.copyOf(referenced); updateRequests();
    }
    public static void requestPreview(List<AssetRef> needed, Set<AssetRef> referenced) {
        previewNeeded = List.copyOf(needed); previewRefs = Set.copyOf(referenced); updateRequests();
    }
    private static void updateRequests() {
        var refs = new HashSet<>(globalRefs); refs.addAll(previewRefs);
        for (var assets : speakerAssets.values()) refs.addAll(assets.refs());
        graphReferenced = Set.copyOf(refs);
        var needed = new java.util.LinkedHashSet<>(globalNeeded); needed.addAll(previewNeeded);
        for (var assets : speakerAssets.values()) needed.addAll(assets.needed());
        queued.retainGraph(List.copyOf(needed));
        long now = System.nanoTime();
        for (AssetRef ref : needed) if (!ref.equals(flight) && now - failed.getOrDefault(ref, now - 31_000_000_000L) > 30_000_000_000L) queued.add(ref, false);
    }
    /** Explicit player-initiated fetch (e.g. /groove-samples install), regardless of graph reference. */
    public static void install(AssetRef ref) {
        failed.remove(ref);
        if (ref.equals(flight)) { flightIsInstall = true; return; }
        if (!queued.add(ref, true)) throw new IllegalArgumentException("Sample install queue is full");
    }
    private static void completeTransfer(SampleTransfer finished, AssetRef ref, long ticket, Minecraft client) {
        decoding = true;
        SampleInstallStore.Session origin = installSession;
        IO.execute(() -> {
            if (origin.cancelled()) return;
            try {
                byte[] data = finished.finish();
                SampleData pcm = SampleDecoder.decode(data);
                String diskFailure = null;
                if (ref.assetId().startsWith("custom:")) {
                    try {
                        var store = new SampleInstallStore(downloadsRoot(),
                                (long)(SampleCatalog.MAX_TOTAL_BYTES * .9), (int)(SampleCatalog.MAX_ASSETS * .9));
                        if (!store.install(ref, data, origin, SampleLibrary::protectedInstallIds)) return;
                        reload();
                    } catch (Exception diskError) {
                        diskFailure = "DISK_INSTALL_FAILED: " + diskError.getMessage();
                        GrooveMod.LOGGER.warn("Could not install sample {} to disk: {}", ref.assetId(), diskError.getMessage());
                    }
                }
                String installError = diskFailure;
                client.execute(() -> commitDecodedSample(ticket, ref, pcm, data, installError));
            } catch (RuntimeException error) {
                GrooveMod.LOGGER.warn("Rejected sample {}: {}", ref.assetId(), error.getMessage());
                client.execute(() -> { if (ticket == session) fail("DECODE_OR_HASH_ERROR: " + error.getMessage()); });
            }
        });
    }
    private static Set<String> protectedInstallIds() {
        Set<String> ids = new HashSet<>();
        for (AssetRef ref : graphReferenced) ids.add(ref.assetId());
        for (AssetRef ref : remoteAvailable) ids.add(ref.assetId());
        return ids;
    }
    private static void commitDecodedSample(long ticket, AssetRef ref, SampleData pcm, byte[] data, String installError) {
        if (ticket != session) return;
        CACHE.put(ref.sha256(), pcm);
        remember(ref, data);
        Map<AssetRef, String> updated = new HashMap<>(errors);
        if (installError == null) updated.remove(ref);
        else updated.put(ref, installError);
        errors = Map.copyOf(updated);
        failed.remove(ref);
        flight = null;
        transfer = null;
        decoding = false;
        MusicClient.refreshSamples();
    }
    private static void fail(String reason) {
        if (flight != null) {
            if (failed.size() >= 128) failed.clear();
            failed.put(flight, System.nanoTime());
            Map<AssetRef, String> updated = new HashMap<>(errors);
            if (updated.size() >= 128) updated.clear();
            updated.put(flight, reason); errors = Map.copyOf(updated);
        }
        flight = null; transfer = null; decoding = false; sentAt = 0;
    }
    private static void remember(AssetRef ref, byte[] data) {
        synchronized (REMOTE) {
            byte[] old = REMOTE.remove(ref); if (old != null) remoteBytes -= old.length;
            while (remoteBytes + data.length > SampleCatalog.MAX_TOTAL_BYTES) {
                var iterator = REMOTE.entrySet().iterator(); remoteBytes -= iterator.next().getValue().length; iterator.remove();
            }
            REMOTE.put(ref, data); remoteBytes += data.length;
        }
    }
}

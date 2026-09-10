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
    private static final Set<AssetRef> queued = new LinkedHashSet<>();
    private static final Map<AssetRef, Long> failed = new HashMap<>();
    private static AssetRef flight;
    private static SampleTransfer transfer;
    private static long sentAt, session, nextSendAt;
    private static int retries;
    private static boolean decoding;
    private static volatile Map<AssetRef, String> errors = Map.of();
    static {
        try { catalog = SampleCatalog.scan(null); } catch (java.io.IOException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
    public static SampleCatalog catalog() { return catalog; }
    public static Executor executor() { return IO; }
    public static Map<AssetRef, String> errors() { return errors; }
    public static void register() {
        IO.scheduleWithFixedDelay(SampleLibrary::reload, 0, 5, TimeUnit.SECONDS);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> IO.shutdownNow());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            session++; queued.clear(); failed.clear(); errors = Map.of(); flight = null; transfer = null; decoding = false;
            synchronized (REMOTE) { REMOTE.clear(); remoteBytes = 0; }
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
            if (client.getConnection() == null || decoding || !ClientPlayNetworking.canSend(MusicPackets.AssetRequest.TYPE)) return;
            if (flight == null && !queued.isEmpty()) {
                var iterator = queued.iterator(); flight = iterator.next(); iterator.remove();
                transfer = null; retries = 0; sentAt = 0;
            }
            long now = System.nanoTime();
            if (flight != null && now >= nextSendAt && (sentAt == 0 || now - sentAt > 2_000_000_000L)) {
                if (++retries > 3) { fail("TRANSFER_TIMEOUT"); return; }
                ClientPlayNetworking.send(new MusicPackets.AssetRequest(flight, transfer == null ? 0 : transfer.offset()));
                sentAt = now;
            }
        });
    }
    public static void reload() {
        try {
            var root = FabricLoader.getInstance().getGameDir().resolve("groove/samples");
            Files.createDirectories(root);
            SampleCatalog next = SampleCatalog.scan(root);
            boolean changed = !next.entries().stream().map(SampleCatalog.Entry::ref).toList()
                    .equals(catalog.entries().stream().map(SampleCatalog.Entry::ref).toList());
            catalog = next;
            if (changed) Minecraft.getInstance().execute(MusicClient::refreshSamples);
        } catch (Exception error) { GrooveMod.LOGGER.warn("Could not scan client sample packs", error); }
    }
    public static SampleData resolve(AssetRef ref) {
        SampleData hit = CACHE.get(ref.sha256());
        if (hit != null) return hit;
        byte[] data = null;
        SampleCatalog current = catalog;
        if (current.status(ref) == SampleCatalog.Status.READY) data = current.find(ref.assetId()).bytes();
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
        var current = new LiveRenderer.Program(bare.current().state(), bare.current().plan(), bank);
        var pending = bare.pending() == null ? null : new LiveRenderer.Program(bare.pending().state(), bare.pending().plan(), bank);
        return new Prepared(new LiveRenderer.Timeline(current, pending), Map.copyOf(status), List.copyOf(needed));
    }
    public static void request(List<AssetRef> needed) {
        queued.retainAll(needed);
        long now = System.nanoTime();
        for (AssetRef ref : needed) if (!ref.equals(flight) && queued.size() < 128 && now - failed.getOrDefault(ref, now - 31_000_000_000L) > 30_000_000_000L) queued.add(ref);
    }
    private static void completeTransfer(SampleTransfer finished, AssetRef ref, long ticket, Minecraft client) {
        decoding = true;
        IO.execute(() -> {
            try {
                byte[] data = finished.finish();
                SampleData pcm = SampleDecoder.decode(data);
                client.execute(() -> commitDecodedSample(ticket, ref, pcm, data));
            } catch (RuntimeException error) {
                GrooveMod.LOGGER.warn("Rejected sample {}: {}", ref.assetId(), error.getMessage());
                client.execute(() -> { if (ticket == session) fail("DECODE_OR_HASH_ERROR: " + error.getMessage()); });
            }
        });
    }
    private static void commitDecodedSample(long ticket, AssetRef ref, SampleData pcm, byte[] data) {
        if (ticket != session) return;
        CACHE.put(ref.sha256(), pcm);
        remember(ref, data);
        Map<AssetRef, String> updated = new HashMap<>(errors);
        updated.remove(ref);
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

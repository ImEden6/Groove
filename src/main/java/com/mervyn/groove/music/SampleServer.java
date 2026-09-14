package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import groove.engine.samples.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.concurrent.*;

/** Shares graph assets automatically and catalog assets on explicit operator requests. */
public final class SampleServer {
    private static volatile SampleCatalog catalog;
    private static ScheduledExecutorService scanner;
    private static volatile long generation;
    private static final Map<UUID, Long> requests = new HashMap<>();
    static boolean hasAsset(AssetRef ref) {
        SampleCatalog current = catalog;
        if (current != null && current.status(ref) == SampleCatalog.Status.READY) return true;
        return FactorySamples.ids().contains(ref.assetId()) && FactorySamples.ref(ref.assetId()).equals(ref);
    }
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            long ticket = ++generation;
            var root = server.getWorldPath(LevelResource.ROOT).resolve("sequencer_samples");
            scanner = Executors.newSingleThreadScheduledExecutor(task -> { var t = new Thread(task, "Groove server samples"); t.setDaemon(true); return t; });
            scanner.scheduleWithFixedDelay(() -> {
                try {
                    var scanned = SampleCatalog.scan(root);
                    server.execute(() -> {
                        if (ticket != generation) return;
                        updateCatalog(scanned, packet -> {
                            for (var player : server.getPlayerList().getPlayers())
                                if (ServerPlayNetworking.canSend(player, MusicPackets.CatalogSnapshot.TYPE))
                                    ServerPlayNetworking.send(player, packet);
                        });
                    });
                }
                catch (Exception error) { GrooveMod.LOGGER.warn("Could not scan server sample packs", error); }
            }, 0, 5, TimeUnit.SECONDS);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { generation++; if (scanner != null) scanner.shutdownNow(); catalog = null; requests.clear(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> requests.remove(handler.player.getUUID()));
        // Only advertises the id+hash listing (no bytes); browsing/discovery, not a bulk push.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SampleCatalog current = catalog;
            if (current == null || !ServerPlayNetworking.canSend(handler.player, MusicPackets.CatalogSnapshot.TYPE)) return;
            ServerPlayNetworking.send(handler.player, catalogSnapshot(current));
        });
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.AssetRequest.TYPE, (packet, context) -> {
            if (!rateLimited(context.player().getUUID())) return;
            serveChunk(context.player(), packet.ref(), packet.offset(),
                    (MusicServer.allowsAsset(packet.ref())
                            || HeadphoneServer.allowsAsset(context.player(), packet.ref())
                            || SpeakerServer.allowsAsset(context.player(), packet.ref())));
        });
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.AssetInstallRequest.TYPE, (packet, context) -> {
            if (!rateLimited(context.player().getUUID())) return;
            serveChunk(context.player(), packet.ref(), packet.offset(), context.player().hasPermissions(2));
        });
    }
    static MusicPackets.CatalogSnapshot catalogSnapshot(SampleCatalog current) {
        return new MusicPackets.CatalogSnapshot(current.entries().stream().map(SampleCatalog.Entry::ref)
                .filter(ref -> ref.assetId().startsWith("custom:")).toList());
    }
    /** Server thread only. Also announces the first scan to players who joined before it finished. */
    static void updateCatalog(SampleCatalog scanned, java.util.function.Consumer<MusicPackets.CatalogSnapshot> broadcast) {
        SampleCatalog before = catalog;
        catalog = scanned;
        var packet = catalogSnapshot(scanned);
        if (before == null || !packet.equals(catalogSnapshot(before))) broadcast.accept(packet);
    }
    private static boolean rateLimited(UUID player) {
        long now = System.nanoTime();
        Long last = requests.get(player);
        if (last != null && now - last < 40_000_000L) return false;
        requests.put(player, now);
        return true;
    }
    private static void serveChunk(net.minecraft.server.level.ServerPlayer player, AssetRef ref, int offset, boolean allowed) {
        SampleCatalog current = catalog;
        var entry = current == null ? null : current.find(ref.assetId());
        if (entry == null || !entry.ref().equals(ref) || !allowed || offset >= entry.size()) {
            ServerPlayNetworking.send(player, new MusicPackets.AssetChunk(ref, 0, 0, new byte[0]));
            return;
        }
        ServerPlayNetworking.send(player, new MusicPackets.AssetChunk(ref, offset, entry.size(), entry.chunk(offset, SampleTransfer.CHUNK_BYTES)));
    }
}

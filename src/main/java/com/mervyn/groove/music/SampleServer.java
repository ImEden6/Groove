package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import groove.engine.samples.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.concurrent.*;

/** Shares only exact assets referenced by the active/pending graph, never arbitrary server files. */
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
                try { var scanned = SampleCatalog.scan(root); if (ticket == generation) catalog = scanned; }
                catch (Exception error) { GrooveMod.LOGGER.warn("Could not scan server sample packs", error); }
            }, 0, 5, TimeUnit.SECONDS);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { generation++; if (scanner != null) scanner.shutdownNow(); catalog = null; requests.clear(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> requests.remove(handler.player.getUUID()));
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.AssetRequest.TYPE, (packet, context) -> {
            long now = System.nanoTime();
            Long last = requests.get(context.player().getUUID());
            if (last != null && now - last < 40_000_000L) return;
            requests.put(context.player().getUUID(), now);
            SampleCatalog current = catalog;
            var entry = current == null ? null : current.find(packet.ref().assetId());
            if (entry == null || !entry.ref().equals(packet.ref()) || !MusicServer.allowsAsset(packet.ref()) || packet.offset() >= entry.size()) {
                ServerPlayNetworking.send(context.player(), new MusicPackets.AssetChunk(packet.ref(), 0, 0, new byte[0]));
                return;
            }
            ServerPlayNetworking.send(context.player(), new MusicPackets.AssetChunk(packet.ref(), packet.offset(), entry.size(), entry.chunk(packet.offset(), SampleTransfer.CHUNK_BYTES)));
        });
    }
}

package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.block.GrooveBlocks;
import com.mervyn.groove.block.SpeakerBlockEntity;
import com.mervyn.groove.music.MusicPackets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import groove.engine.ClockSync;
import groove.engine.LiveRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

public final class MusicClient {
    private static final ThreadPoolExecutor COMPILER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "Groove graph compiler"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Groove lookahead"); thread.setDaemon(true); return thread;
    });
    private static LiveRenderer.Timeline failedSchedule;
    private static UUID epoch;
    private static long revision = -1, generation, lastPing, outstandingPing;
    private static volatile ClockSync clock = new ClockSync();
    private static LiveRenderer renderer = new LiveRenderer();
    private static volatile LiveRenderer.Timeline program;
    private static groove.engine.SessionTimeline.Snapshot latestSnapshot;
    private static MusicPackets.Snapshot latestWire;
    private static java.util.Map<groove.engine.samples.AssetRef, String> sampleStatus = java.util.Map.of();
    private static GrooveSound sound;
    private static GrooveAudioStream stream;
    private static int retryTicks;
    private static final Set<BlockPos> speakerSegments = new HashSet<>();
    private static final Map<BlockPos, Emitter> emitters = new HashMap<>();
    private static final int MAX_EMITTERS = 8;
    private static final double MAX_AUDIBLE_DIST_SQR = 64.0 * 64.0;
    private static int emitterScanCooldown;

    public static void register() {
        SCHEDULER.scheduleWithFixedDelay(() -> {
            var active = program;
            if (active == null) { failedSchedule = null; return; }
            if (active == failedSchedule) return;
            try { active.prepare(serverNow()); failedSchedule = null; }
            catch (RuntimeException error) {
                failedSchedule = active;
                GrooveMod.LOGGER.error("Groove lookahead preparation failed", error);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.SubmitResult.TYPE, (packet, context) -> {
            if (context.client().screen instanceof com.mervyn.groove.client.ui.GrooveEditorScreen editor) editor.submissionResult(packet);
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.Snapshot.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                if (!packet.epoch().equals(epoch)) {
                    reset(context.client());
                    epoch = packet.epoch();
                }
                if (packet.revision() <= revision) return;
                revision = packet.revision();
                latestWire = packet;
                refreshSamples();
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.Pong.TYPE, (packet, context) -> {
            if (packet.sent() == outstandingPing) {
                clock.observe(packet.sent(), System.nanoTime(), packet.serverNanos());
                outstandingPing = 0;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> addSpeakers(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> removeSpeakers(level, chunk));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { reset(client); COMPILER.shutdownNow(); SCHEDULER.shutdownNow(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getConnection() == null || !ClientPlayNetworking.canSend(MusicPackets.Ping.TYPE)) return;
            long now = System.nanoTime();
            if (lastPing == 0 || now - lastPing >= 1_000_000_000L) {
                lastPing = now;
                outstandingPing = now;
                ClientPlayNetworking.send(new MusicPackets.Ping(now));
            }
            if (client.level == null || client.player == null || program == null || !clock.ready() || client.isPaused()
                    || client.getOverlay() != null || !client.getSoundManager().getAvailableSounds().contains(GrooveMod.id("session"))) {
                stopEmitters(client);
                return;
            }
            updateEmitters(client);
            if (!emitters.isEmpty()) {
                stopMonitor(client);
                return;
            }
            if (retryTicks > 0) { retryTicks--; return; }
            if (sound == null || stream.closed() || !client.getSoundManager().isActive(sound)) {
                if (sound != null) client.getSoundManager().stop(sound);
                if (stream != null) stream.close();
                renderer = new LiveRenderer();
                renderer.publish(program);
                stream = new GrooveAudioStream(renderer, clock);
                sound = new GrooveSound(stream);
                client.getSoundManager().play(sound);
                retryTicks = 100;
            }
        });
    }
    public static java.util.Map<groove.engine.samples.AssetRef, String> sampleStatus() { return sampleStatus; }
    public static UUID epoch() { return epoch; }
    public static groove.engine.SessionTimeline.Snapshot snapshot() { return latestSnapshot; }
    public static long serverNow() { return clock.serverTime(System.nanoTime()); }
    public static groove.engine.SessionState desiredState() {
        return latestSnapshot == null ? null : latestSnapshot.pending() == null ? latestSnapshot.current() : latestSnapshot.pending();
    }
    public static void refreshSamples() {
        var wire = latestWire;
        if (wire == null || COMPILER.isShutdown()) return;
        long ticket = ++generation;
        COMPILER.execute(() -> {
            try {
                var snapshot = wire.snapshot();
                var prepared = SampleLibrary.prepare(snapshot);
                prepared.timeline().prepare(serverNow());
                Minecraft.getInstance().execute(() -> {
                    if (ticket != generation) return;
                    latestSnapshot = snapshot;
                    program = prepared.timeline(); sampleStatus = prepared.status();
                    renderer.publish(program);
                    for (Emitter emitter : emitters.values()) emitter.renderer.publish(program);
                    SampleLibrary.request(prepared.needed());
                });
            } catch (RuntimeException error) {
                Minecraft.getInstance().execute(() -> { if (ticket == generation) revision = -1; });
                GrooveMod.LOGGER.error("Rejected Groove session", error);
            }
        });
    }
    private static void reset(Minecraft client) {
        generation++; revision = -1; epoch = null; program = null;
        latestSnapshot = null; latestWire = null; sampleStatus = java.util.Map.of();
        lastPing = 0; outstandingPing = 0;
        stopMonitor(client);
        stopEmitters(client);
        speakerSegments.clear();
        emitterScanCooldown = 0;
        clock = new ClockSync(); renderer = new LiveRenderer();
        COMPILER.getQueue().clear();
    }

    private static void addSpeakers(LevelChunk chunk) {
        boolean added = false;
        for (var entry : chunk.getBlockEntities().entrySet()) {
            if (entry.getValue() instanceof SpeakerBlockEntity) {
                speakerSegments.add(entry.getKey().immutable());
                added = true;
            }
        }
        if (added) emitterScanCooldown = 0;
    }

    private static void removeSpeakers(net.minecraft.client.multiplayer.ClientLevel level, LevelChunk chunk) {
        boolean removed = speakerSegments.removeIf(pos -> pos.getX() >> 4 == chunk.getPos().x && pos.getZ() >> 4 == chunk.getPos().z);
        emitters.entrySet().removeIf(entry -> {
            if (entry.getKey().getX() >> 4 != chunk.getPos().x || entry.getKey().getZ() >> 4 != chunk.getPos().z)
                return false;
            stopEmitter(Minecraft.getInstance(), entry.getValue());
            return true;
        });
        if (removed) emitterScanCooldown = 0;
    }

    private static void updateEmitters(Minecraft client) {
        if (emitterScanCooldown > 0) {
            emitterScanCooldown--;
            return;
        }
        emitterScanCooldown = 10;
        BlockPos playerPos = client.player.blockPosition();
        Set<BlockPos> bases = new HashSet<>();
        for (BlockPos pos : speakerSegments) {
            if (pos.distSqr(playerPos) > MAX_AUDIBLE_DIST_SQR) continue;
            if (!client.level.getBlockState(pos).is(GrooveBlocks.SPEAKER)
                    || client.level.getBlockState(pos.below()).is(GrooveBlocks.SPEAKER)) continue;
            bases.add(pos);
        }
        bases.stream().sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(playerPos)))
                .limit(MAX_EMITTERS).forEach(pos -> {
                    int height = towerHeight(client, pos);
                    Emitter emitter = emitters.get(pos);
                    if (emitter != null && emitter.height == height && !emitter.stream.closed()
                            && client.getSoundManager().isActive(emitter.sound)) return;
                    if (emitter != null) stopEmitter(client, emitter);
                    LiveRenderer sourceRenderer = new LiveRenderer();
                    sourceRenderer.publish(program);
                    GrooveAudioStream sourceStream = new GrooveAudioStream(sourceRenderer, clock, true);
                    GrooveSound sourceSound = new GrooveSound(sourceStream, pos, height);
                    emitters.put(pos, new Emitter(sourceSound, sourceStream, height, sourceRenderer));
                    client.getSoundManager().play(sourceSound);
                });
        emitters.entrySet().removeIf(entry -> {
            if (bases.contains(entry.getKey())) return false;
            stopEmitter(client, entry.getValue());
            return true;
        });
    }

    private static int towerHeight(Minecraft client, BlockPos base) {
        int height = 1;
        while (height < 32 && client.level.getBlockState(base.above(height)).is(GrooveBlocks.SPEAKER)) height++;
        return height;
    }

    private static void stopMonitor(Minecraft client) {
        if (sound != null) client.getSoundManager().stop(sound);
        if (stream != null) stream.close();
        sound = null; stream = null; retryTicks = 0;
    }

    private static void stopEmitters(Minecraft client) {
        for (Emitter emitter : emitters.values()) stopEmitter(client, emitter);
        emitters.clear();
    }

    private static void stopEmitter(Minecraft client, Emitter emitter) {
        client.getSoundManager().stop(emitter.sound);
        emitter.stream.close();
    }

    private record Emitter(GrooveSound sound, GrooveAudioStream stream, int height, LiveRenderer renderer) {}
}

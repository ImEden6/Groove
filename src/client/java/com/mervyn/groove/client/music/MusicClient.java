package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.block.GrooveBlocks;
import com.mervyn.groove.block.GrooveItems;
import com.mervyn.groove.block.SpeakerBlockEntity;
import com.mervyn.groove.music.GraphJson;
import com.mervyn.groove.music.HeadphoneLinks;
import com.mervyn.groove.music.HeadphonePackets;
import com.mervyn.groove.music.MusicPackets;
import com.mervyn.groove.music.SpeakerPackets;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import groove.engine.ClockSync;
import groove.engine.LiveRenderer;
import groove.engine.SessionState;
import groove.engine.SessionTimeline;
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
    private static final ThreadPoolExecutor PREVIEW_COMPILER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> { Thread thread = new Thread(task, "Groove preview compiler"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Groove lookahead"); thread.setDaemon(true); return thread;
    });
    private static LiveRenderer.Timeline failedSchedule, failedPreviewSchedule;
    private static HeadphonePackets.Draft previewWire;
    private static UUID epoch;
    private static long revision = -1, generation, lastPing, outstandingPing;
    private static volatile ClockSync clock = new ClockSync();
    private static volatile LiveRenderer.Timeline program;
    private static groove.engine.SessionTimeline.Snapshot latestSnapshot;
    private static MusicPackets.Snapshot latestWire;
    private static java.util.Map<groove.engine.samples.AssetRef, String> sampleStatus = java.util.Map.of();
    private static HeadphoneLinks.Link previewLink;
    private static UUID previewRequest;
    private static long previewSent;
    private static long previewRevision = -1;
    private static long previewGeneration;
    private static volatile LiveRenderer.Timeline previewProgram;
    private static LiveRenderer previewRenderer = new LiveRenderer();
    private static GrooveSound previewSound;
    private static GrooveAudioStream previewStream;
    private static int previewRetryTicks;
    private static final Set<BlockPos> speakerSegments = new HashSet<>();
    private static final Map<BlockPos, Emitter> emitters = new HashMap<>();
    private static final Map<BlockPos, SpeakerLink> speakerLinks = new HashMap<>();
    private static final int MAX_EMITTERS = 8;
    private static final double MAX_AUDIBLE_DIST_SQR = 64.0 * 64.0;
    private static int emitterScanCooldown;

    public static void register() {
        SCHEDULER.scheduleWithFixedDelay(() -> {
            var active = program;
            if (active != null && active != failedSchedule) {
                try { active.prepare(serverNow()); failedSchedule = null; }
                catch (RuntimeException error) { failedSchedule = active; GrooveMod.LOGGER.error("Groove lookahead preparation failed", error); }
            }
            var preview = previewProgram;
            if (preview != null && preview != failedPreviewSchedule) {
                try { preview.prepare(serverNow()); failedPreviewSchedule = null; }
                catch (RuntimeException error) { failedPreviewSchedule = preview; GrooveMod.LOGGER.error("Headphone lookahead preparation failed", error); }
            }
            long now = serverNow();
            for (SpeakerLink link : speakerLinks.values()) {
                var speakerProg = link.program;
                if (speakerProg != null) {
                    try { speakerProg.prepare(now); }
                    catch (RuntimeException error) { GrooveMod.LOGGER.error("Speaker lookahead preparation failed", error); }
                }
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
        ClientPlayNetworking.registerGlobalReceiver(HeadphonePackets.Draft.TYPE, (packet, context) -> context.client().execute(() -> {
            if (!packet.request().equals(previewRequest)) return;
            previewRequest = null;
            if (!packet.available() || previewLink == null || !packet.session().equals(previewLink.session())) {
                var link = previewLink;
                stopHeadphonePreview(context.client());
                previewLink = link; previewSent = System.nanoTime();
                return;
            }
            if (packet.revision() <= previewRevision) return;
            previewRevision = packet.revision();
            previewWire = packet;
            refreshPreview();
        }));
        ClientPlayNetworking.registerGlobalReceiver(SpeakerPackets.CommittedState.TYPE, (packet, context) -> context.client().execute(() -> {
            var link = speakerLinks.get(packet.pos());
            if (link == null || !packet.request().equals(link.request)) return;
            link.request = null;
            if (!packet.available()) { link.program = null; link.revision = -1; return; }
            if (packet.revision() <= link.revision) return;
            link.revision = packet.revision();
            try {
                var graph = GraphJson.decode(packet.graph());
                var state = new SessionState(packet.revision(), packet.at(), packet.cycle(), packet.bpm(), packet.playing(), graph);
                var snapshot = new SessionTimeline.Snapshot(state, null);
                long ticket = ++link.generation;
                COMPILER.execute(() -> {
                    try {
                        var prepared = SampleLibrary.prepare(snapshot);
                        prepared.timeline().prepare(serverNow());
                        Minecraft.getInstance().execute(() -> {
                            if (ticket != link.generation) return;
                            link.program = prepared.timeline();
                            var emitter = emitters.get(packet.pos());
                            if (emitter != null) emitter.renderer.publish(link.program);
                            SampleLibrary.request(prepared.needed(), prepared.status().keySet());
                        });
                    } catch (RuntimeException error) {
                        GrooveMod.LOGGER.error("Rejected speaker committed patch", error);
                    }
                });
            } catch (RuntimeException error) {
                // Leave whatever this speaker was already playing; retry on the next poll.
            }
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> addSpeakers(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> removeSpeakers(level, chunk));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { reset(client); COMPILER.shutdownNow(); PREVIEW_COMPILER.shutdownNow(); SCHEDULER.shutdownNow(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getConnection() == null || !ClientPlayNetworking.canSend(MusicPackets.Ping.TYPE)) return;
            long now = System.nanoTime();
            if (lastPing == 0 || now - lastPing >= 1_000_000_000L) {
                lastPing = now;
                outstandingPing = now;
                ClientPlayNetworking.send(new MusicPackets.Ping(now));
            }
            if (client.level == null || client.player == null || !clock.ready() || client.isPaused()
                    || client.getOverlay() != null || !client.getSoundManager().getAvailableSounds().contains(GrooveMod.id("session"))) {
                stopEmitters(client); stopHeadphonePreview(client);
                return;
            }
            if (SampleCommands.auditioning()) {
                stopEmitters(client);
                stopHeadphonePreview(client);
                return;
            }
            boolean headphones = TrinketsApi.getTrinketComponent(client.player)
                    .map(component -> component.isEquipped(GrooveItems.HEADPHONES))
                    .orElse(false);
            if (headphones) {
                if (!emitters.isEmpty()) stopEmitters(client);
                updateHeadphonePreview(client);
            } else {
                stopHeadphonePreview(client);
                updateEmitters(client);
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
        refreshPreview();
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
                    SampleLibrary.request(prepared.needed(), prepared.status().keySet());
                });
            } catch (RuntimeException error) {
                Minecraft.getInstance().execute(() -> { if (ticket == generation) revision = -1; });
                GrooveMod.LOGGER.error("Rejected Groove session", error);
            }
        });
    }
    private static void refreshPreview() {
        var packet = previewWire;
        if (packet == null || PREVIEW_COMPILER.isShutdown()) return;
        long ticket = ++previewGeneration;
        PREVIEW_COMPILER.execute(() -> {
            try {
                // Parsing, validation, sample decoding, and lookahead all stay off the UI thread.
                var draft = GraphJson.decodeDraft(packet.graph());
                var state = new SessionState(packet.revision(), packet.at(), packet.cycle(), packet.bpm(), packet.playing(),
                        draft);
                var prepared = SampleLibrary.prepare(new SessionTimeline.Snapshot(state, null));
                prepared.timeline().prepare(serverNow());
                Minecraft.getInstance().execute(() -> {
                    if (ticket != previewGeneration) return;
                    previewProgram = prepared.timeline();
                    previewRenderer.publish(previewProgram);
                    SampleLibrary.requestPreview(prepared.needed(), prepared.status().keySet());
                });
            } catch (RuntimeException error) {
                Minecraft.getInstance().execute(() -> {
                    if (ticket == previewGeneration) silencePreview(Minecraft.getInstance());
                });
            }
        });
    }
    private static void reset(Minecraft client) {
        generation++; revision = -1; epoch = null; program = null;
        latestSnapshot = null; latestWire = null; sampleStatus = java.util.Map.of();
        lastPing = 0; outstandingPing = 0;
        stopEmitters(client);
        stopHeadphonePreview(client);
        speakerSegments.clear();
        speakerLinks.clear();
        emitterScanCooldown = 0;
        clock = new ClockSync();
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
        speakerLinks.keySet().removeIf(pos -> pos.getX() >> 4 == chunk.getPos().x && pos.getZ() >> 4 == chunk.getPos().z);
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
        // Poll every nearby base, not just already-linked ones, so a fresh bind picks up automatically.
        for (BlockPos pos : bases) pollSpeakerLink(pos);
        bases.stream()
                .filter(pos -> { SpeakerLink link = speakerLinks.get(pos); return link != null && link.program != null; })
                .sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(playerPos)))
                .limit(MAX_EMITTERS).forEach(pos -> {
                    int height = towerHeight(client, pos);
                    Emitter emitter = emitters.get(pos);
                    if (emitter != null && emitter.height == height && !emitter.stream.closed()
                            && client.getSoundManager().isActive(emitter.sound)) return;
                    if (emitter != null) stopEmitter(client, emitter);
                    LiveRenderer sourceRenderer = new LiveRenderer();
                    sourceRenderer.publish(speakerLinks.get(pos).program);
                    GrooveAudioStream sourceStream = new GrooveAudioStream(sourceRenderer, clock, true);
                    GrooveSound sourceSound = new GrooveSound(sourceStream, pos, height);
                    emitters.put(pos, new Emitter(sourceSound, sourceStream, height, sourceRenderer));
                    client.getSoundManager().play(sourceSound);
                });
        emitters.entrySet().removeIf(entry -> {
            SpeakerLink link = speakerLinks.get(entry.getKey());
            if (bases.contains(entry.getKey()) && link != null && link.program != null) return false;
            stopEmitter(client, entry.getValue());
            return true;
        });
        // Cached link state for bases no longer nearby is dropped so re-entering range re-polls fresh.
        speakerLinks.keySet().removeIf(pos -> !bases.contains(pos));
    }

    private static void pollSpeakerLink(BlockPos pos) {
        SpeakerLink link = speakerLinks.computeIfAbsent(pos, p -> new SpeakerLink());
        long now = System.nanoTime();
        if ((link.request != null && now - link.sent < 3_000_000_000L)
                || now - link.sent < 1_000_000_000L
                || !ClientPlayNetworking.canSend(SpeakerPackets.CommittedRequest.TYPE))
            return;
        link.request = UUID.randomUUID(); link.sent = now;
        ClientPlayNetworking.send(new SpeakerPackets.CommittedRequest(pos, link.request));
    }

    private static int towerHeight(Minecraft client, BlockPos base) {
        int height = 1;
        while (height < 32 && client.level.getBlockState(base.above(height)).is(GrooveBlocks.SPEAKER)) height++;
        return height;
    }

    /** Returns whether headphones are usably linked right now; audible or not (a fresh
     *  link plays nothing until the first draft response lands). */
    private static boolean updateHeadphonePreview(Minecraft client) {
        var worn = TrinketsApi.getTrinketComponent(client.player)
                .map(component -> component.getEquipped(GrooveItems.HEADPHONES))
                .filter(equipped -> !equipped.isEmpty())
                .map(equipped -> equipped.getFirst().getB());
        var link = worn.isEmpty() ? java.util.Optional.<HeadphoneLinks.Link>empty() : HeadphoneLinks.read(worn.get());
        if (link.isEmpty() || client.level == null
                || !HeadphoneLinks.inRange(link.get(), client.level.dimension().location(), client.player.position())) {
            stopHeadphonePreview(client);
            return false;
        }
        if (!link.get().equals(previewLink)) {
            stopHeadphonePreview(client);
            previewLink = link.get();
        }
        long now = System.nanoTime();
        if (previewRequest != null && now - previewSent > 5_000_000_000L) {
            stopHeadphonePreview(client); previewLink = link.get();
        }
        if (previewRequest == null && now - previewSent > 500_000_000L && ClientPlayNetworking.canSend(HeadphonePackets.Preview.TYPE)) {
            previewRequest = UUID.randomUUID(); previewSent = now;
            ClientPlayNetworking.send(new HeadphonePackets.Preview(previewRequest));
        }
        if (previewRetryTicks > 0) { previewRetryTicks--; return true; }
        if (previewProgram != null && (previewSound == null || previewStream.closed() || !client.getSoundManager().isActive(previewSound))) {
            if (previewSound != null) client.getSoundManager().stop(previewSound);
            if (previewStream != null) previewStream.close();
            previewRenderer = new LiveRenderer();
            previewRenderer.publish(previewProgram);
            previewStream = new GrooveAudioStream(previewRenderer, clock);
            previewSound = new GrooveSound(previewStream);
            client.getSoundManager().play(previewSound);
            previewRetryTicks = 100;
        }
        return true;
    }

    private static void silencePreview(Minecraft client) {
        if (previewSound != null) client.getSoundManager().stop(previewSound);
        if (previewStream != null) previewStream.close();
        previewSound = null; previewStream = null; previewRetryTicks = 0;
        previewProgram = null;
        SampleLibrary.requestPreview(java.util.List.of(), java.util.Set.of());
    }
    private static void stopHeadphonePreview(Minecraft client) {
        if (previewLink == null && previewRequest == null && previewWire == null && previewProgram == null && previewSound == null) return;
        // Invalidate workers before clearing the stream so an old completion cannot revive it.
        previewGeneration++;
        PREVIEW_COMPILER.getQueue().clear();
        silencePreview(client);
        previewLink = null; previewRequest = null; previewSent = 0; previewRevision = -1; previewWire = null;
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

    /** One speaker's polled link state: what it should play, if anything, and the in-flight
     *  request bookkeeping to avoid re-polling every tick. */
    private static final class SpeakerLink {
        UUID request;
        long sent;
        long revision = -1;
        long generation;
        volatile LiveRenderer.Timeline program;
    }
}

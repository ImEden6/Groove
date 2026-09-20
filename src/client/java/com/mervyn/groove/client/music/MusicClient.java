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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import groove.engine.ClockSync;
import groove.engine.LiveRenderer;
import groove.engine.ReplayBudget;
import groove.engine.SessionState;
import groove.engine.SessionTimeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.mervyn.groove.music.GrooveProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
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
    private static final ThreadPoolExecutor SPEAKER_COMPILER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> { Thread thread = new Thread(task, "Groove speaker compiler"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private static net.minecraft.client.multiplayer.ClientLevel speakerLevel;
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
    private static final int MAX_EMITTERS = 8;
    /** Every speaker emitter shares one budget: 8 active plus up to 16 fading renderers. */
    private static final ReplayBudget SPEAKER_REPLAY = new ReplayBudget(MAX_EMITTERS + MAX_EMITTERS * 2);
    private static final ReplayLog SPEAKER_REPLAY_LOG = new ReplayLog("Speaker");
    private static final ReplayLog PREVIEW_REPLAY_LOG = new ReplayLog("Headphone");
    /** The preview plays one renderer at a time, so each preview renderer gets its own budget. */
    private static LiveRenderer previewRenderer = new LiveRenderer(new ReplayBudget(1));
    private static ReplayBudget previewReplay;
    private static GrooveSound previewSound;
    private static GrooveAudioStream previewStream;
    private static int previewRetryTicks;
    private static final Set<BlockPos> speakerSegments = new HashSet<>();
    private static final Map<Emitter, Long> fadingEmitters = new java.util.IdentityHashMap<>();
    private static final Map<BlockPos, Emitter> emitters = new HashMap<>();
    private static final Map<BlockPos, SpeakerLink> speakerLinks = new ConcurrentHashMap<>();
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
                if (speakerProg != null && speakerProg != link.failedSchedule) {
                    try { speakerProg.prepare(now); }
                    catch (RuntimeException error) { link.failedSchedule = speakerProg; GrooveMod.LOGGER.error("Speaker lookahead preparation failed", error); }
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        ClientConfigurationNetworking.registerGlobalReceiver(MusicPackets.Protocol.TYPE, (packet, context) -> {
            context.responseSender().sendPacket(new MusicPackets.Protocol(GrooveProtocol.VERSION));
        });
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
            if (!packet.available()) { invalidateSpeaker(packet.pos(), link); return; }
            if (packet.samePublication(link.wire)) {
                if (link.program == null) compileSpeaker(packet.pos(), link);
                return;
            }
            if (link.wire != null && !link.wire.timeline().epoch().equals(packet.timeline().epoch()))
                invalidateSpeaker(packet.pos(), link);
            link.wire = packet;
            link.generation++;
            compileSpeaker(packet.pos(), link);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> addSpeakers(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> removeSpeakers(level, chunk));
        // A speaker placed or broken in a chunk that is already loaded never reaches the chunk events
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (level != speakerLevel || !(entity instanceof SpeakerBlockEntity)) return;
            if (speakerSegments.add(entity.getBlockPos().immutable())) emitterScanCooldown = 0;
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            if (level != speakerLevel || !(entity instanceof SpeakerBlockEntity)) return;
            dropSpeaker(entity.getBlockPos().immutable());
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { reset(client); COMPILER.shutdownNow(); PREVIEW_COMPILER.shutdownNow(); SPEAKER_COMPILER.shutdownNow(); SCHEDULER.shutdownNow(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            reapFadingEmitters(client);
            SPEAKER_REPLAY_LOG.update(SPEAKER_REPLAY);
            if (previewReplay != null) PREVIEW_REPLAY_LOG.update(previewReplay);
            if (client.getConnection() == null || !ClientPlayNetworking.canSend(MusicPackets.Ping.TYPE)) return;
            if (speakerLevel != client.level) {
                stopEmitters(client);
                clearSpeakerLinks();
                speakerSegments.clear(); emitterScanCooldown = 0;
                speakerLevel = client.level;
                // Chunk load callbacks populate the new level's segments.
            }
            long now = System.nanoTime();
            if (lastPing == 0 || now - lastPing >= 1_000_000_000L) {
                lastPing = now;
                outstandingPing = now;
                ClientPlayNetworking.send(new MusicPackets.Ping(now));
            }
            if (client.level == null || client.player == null || !clock.ready() || client.isPaused()
                    || client.getOverlay() != null || !client.getSoundManager().getAvailableSounds().contains(GrooveMod.id("session"))) {
                stopEmitters(client); clearSpeakerLinks(); stopHeadphonePreview(client);
                return;
            }
            if (SampleCommands.auditioning()) {
                stopEmitters(client); clearSpeakerLinks();
                stopHeadphonePreview(client);
                return;
            }
            boolean headphones = TrinketsApi.getTrinketComponent(client.player)
                    .map(component -> component.isEquipped(GrooveItems.HEADPHONES))
                    .orElse(false);
            if (headphones) {
                stopEmitters(client);
                clearSpeakerLinks();
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
        speakerLinks.forEach(MusicClient::compileSpeaker);
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
    private static void compileSpeaker(BlockPos pos, SpeakerLink link) {
        var wire = link.wire;
        if (wire == null || SPEAKER_COMPILER.isShutdown()) return;
        if (link.compiling) { link.refreshAgain = true; return; }
        link.compiling = true;
        long ticket = link.generation;
        try {
            SPEAKER_COMPILER.execute(() -> {
                SampleLibrary.Prepared prepared = null;
                try {
                    prepared = SampleLibrary.prepare(wire.timeline().snapshot());
                    prepared.timeline().prepare(serverNow());
                } catch (RuntimeException error) { GrooveMod.LOGGER.error("Rejected speaker committed patch", error); }
                var result = prepared;
                Minecraft.getInstance().execute(() -> {
                    link.compiling = false;
                    if (speakerLinks.get(pos) != link) return;
                    if (ticket == link.generation && result == null) { invalidateSpeaker(pos, link); return; }
                    if (ticket == link.generation && result != null) {
                        link.program = result.timeline();
                        var emitter = emitters.get(pos);
                        if (emitter != null) emitter.renderer.publish(link.program, speakerSession(link));
                        SampleLibrary.requestSpeaker(pos, result.needed(), result.status().keySet());
                    }
                    if (link.refreshAgain) { link.refreshAgain = false; compileSpeaker(pos, link); }
                });
            });
        } catch (RejectedExecutionException busy) { link.compiling = false; }
    }
    /** Effect tails only carry between publishes of one committed session. */
    private static UUID speakerSession(SpeakerLink link) {
        return link.wire == null ? null : link.wire.timeline().epoch();
    }
    private static void invalidateSpeaker(BlockPos pos, SpeakerLink link) {
        link.generation++; link.wire = null; link.program = null; link.refreshAgain = false;
        var emitter = emitters.remove(pos);
        if (emitter != null) stopEmitter(emitter);
        SampleLibrary.removeSpeaker(pos);
    }
    private static void clearSpeakerLinks() {
        speakerLinks.forEach(MusicClient::invalidateSpeaker);
        speakerLinks.clear();
        emitterScanCooldown = 0;
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
                    previewRenderer.publish(previewProgram, packet.session());
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
        clearSpeakerLinks();
        emitterScanCooldown = 0;
        clock = new ClockSync();
        COMPILER.getQueue().clear();
    }

    private static void addSpeakers(LevelChunk chunk) {
        var level = Minecraft.getInstance().level;
        if (speakerLevel != level) {
            stopEmitters(Minecraft.getInstance()); clearSpeakerLinks(); speakerSegments.clear(); speakerLevel = level;
        }
        boolean added = false;
        for (var entry : chunk.getBlockEntities().entrySet()) {
            if (entry.getValue() instanceof SpeakerBlockEntity) {
                speakerSegments.add(entry.getKey().immutable());
                added = true;
            }
        }
        if (added) emitterScanCooldown = 0;
    }

    /** Forgets one speaker: its segment, link, emitter and pending samples. */
    private static void dropSpeaker(BlockPos pos) {
        speakerSegments.remove(pos);
        SpeakerLink link = speakerLinks.remove(pos);
        if (link != null) invalidateSpeaker(pos, link);
        emitterScanCooldown = 0;
    }

    private static void removeSpeakers(net.minecraft.client.multiplayer.ClientLevel level, LevelChunk chunk) {
        if (level != speakerLevel) return;
        boolean removed = speakerSegments.removeIf(pos -> pos.getX() >> 4 == chunk.getPos().x && pos.getZ() >> 4 == chunk.getPos().z);
        emitters.entrySet().removeIf(entry -> {
            if (entry.getKey().getX() >> 4 != chunk.getPos().x || entry.getKey().getZ() >> 4 != chunk.getPos().z)
                return false;
            stopEmitter(entry.getValue());
            return true;
        });
        speakerLinks.entrySet().removeIf(entry -> {
            var pos = entry.getKey();
            if (pos.getX() >> 4 != chunk.getPos().x || pos.getZ() >> 4 != chunk.getPos().z) return false;
            invalidateSpeaker(pos, entry.getValue()); return true;
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
            if (client.player.position().distanceToSqr(pos.getCenter()) > MAX_AUDIBLE_DIST_SQR) continue;
            if (!client.level.getBlockState(pos).is(GrooveBlocks.SPEAKER)
                    || client.level.getBlockState(pos.below()).is(GrooveBlocks.SPEAKER)) continue;
            bases.add(pos);
        }
        // One bounded selected set controls polling, compilation and audible emitters.
        Set<BlockPos> selected = nearestSpeakers(bases, client.player.position());
        speakerLinks.entrySet().removeIf(entry -> {
            if (selected.contains(entry.getKey())) return false;
            invalidateSpeaker(entry.getKey(), entry.getValue()); return true;
        });
        for (BlockPos pos : selected) pollSpeakerLink(pos);
        selected.stream()
                .filter(pos -> { SpeakerLink link = speakerLinks.get(pos); return link != null && link.program != null; })
                .sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(playerPos)))
                .limit(MAX_EMITTERS).forEach(pos -> {
                    int height = towerHeight(client, pos);
                    Emitter emitter = emitters.get(pos);
                    if (emitter != null && emitter.height == height && !emitter.stream.closed()
                            && client.getSoundManager().isActive(emitter.sound)) return;
                    if (emitter != null) stopEmitter(emitter);
                    LiveRenderer sourceRenderer = new LiveRenderer(SPEAKER_REPLAY);
                    sourceRenderer.publish(speakerLinks.get(pos).program, speakerSession(speakerLinks.get(pos)));
                    GrooveAudioStream sourceStream = new GrooveAudioStream(sourceRenderer, clock, true);
                    GrooveSound sourceSound = new GrooveSound(sourceStream, pos, height);
                    emitters.put(pos, new Emitter(sourceSound, sourceStream, height, sourceRenderer));
                    client.getSoundManager().play(sourceSound);
                });
        emitters.entrySet().removeIf(entry -> {
            SpeakerLink link = speakerLinks.get(entry.getKey());
            if (selected.contains(entry.getKey()) && link != null && link.program != null) return false;
            stopEmitter(entry.getValue());
            return true;
        });

    }

    public static Set<BlockPos> nearestSpeakers(java.util.Collection<BlockPos> candidates, net.minecraft.world.phys.Vec3 player) {
        return candidates.stream().filter(pos -> player.distanceToSqr(pos.getCenter()) <= MAX_AUDIBLE_DIST_SQR)
                .sorted(java.util.Comparator.<BlockPos>comparingDouble(pos -> player.distanceToSqr(pos.getCenter())).thenComparingLong(BlockPos::asLong))
                .limit(MAX_EMITTERS).collect(java.util.stream.Collectors.toSet());
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
        boolean underwater = client.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        if (previewStream != null) previewStream.setUnderwater(underwater);
        if (previewRetryTicks > 0) { previewRetryTicks--; return true; }
        if (previewProgram != null && (previewSound == null || previewStream.closed() || !client.getSoundManager().isActive(previewSound))) {
            if (previewSound != null) client.getSoundManager().stop(previewSound);
            if (previewStream != null) previewStream.close();
            previewReplay = new ReplayBudget(1);
            previewRenderer = new LiveRenderer(previewReplay);
            previewRenderer.publish(previewProgram, previewLink.session());
            previewStream = new GrooveAudioStream(previewRenderer, clock);
            previewStream.setUnderwater(underwater);
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
        // Pause, disconnect, audition and headphones require immediate silence, including
        // streams already removed from the active map while fading out.
        for (Emitter emitter : emitters.values()) closeEmitter(client, emitter);
        for (Emitter emitter : fadingEmitters.keySet()) closeEmitter(client, emitter);
        emitters.clear(); fadingEmitters.clear();
    }
    private static void closeEmitter(Minecraft client, Emitter emitter) {
        client.getSoundManager().stop(emitter.sound);
        emitter.stream.close();
    }
    private static void stopEmitter(Emitter emitter) {
        emitter.stream.fadeOut(GrooveAudioStream.FADE_FRAMES);
        fadingEmitters.putIfAbsent(emitter, System.nanoTime());
        // Bound retired streams even during rapid relinks or tower changes.
        if (fadingEmitters.size() > MAX_EMITTERS * 2) {
            var oldest = fadingEmitters.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
            closeEmitter(Minecraft.getInstance(), oldest); fadingEmitters.remove(oldest);
        }
    }
    private static void reapFadingEmitters(Minecraft client) {
        long now = System.nanoTime();
        fadingEmitters.entrySet().removeIf(entry -> {
            var emitter = entry.getKey();
            // A paused/stalled sound channel may never consume the fade; retain a bounded
            // fallback deadline. Do not cut queued PCM merely because EOF was generated.
            if (now - entry.getValue() < 2_000_000_000L
                    && !(emitter.stream.closed() && !client.getSoundManager().isActive(emitter.sound))) return false;
            closeEmitter(client, emitter); return true;
        });
    }

    private record Emitter(GrooveSound sound, GrooveAudioStream stream, int height, LiveRenderer renderer) {}

    /** Logs replay lease activity on the main thread; the sound thread only updates counters. */
    static final class ReplayLog {
        private final String name;
        private ReplayBudget budget;
        private long grants, reclaims, evictions, maxWaitFrames;

        ReplayLog(String name) { this.name = name; }

        void update(ReplayBudget current) {
            if (current != budget) { budget = current; grants = reclaims = evictions = maxWaitFrames = 0; }
            long nowGrants = current.grants(), nowReclaims = current.reclaims();
            long nowEvictions = current.evictions(), nowMaxWait = current.maxWaitFrames();
            if (nowGrants != grants || nowReclaims != reclaims)
                GrooveMod.LOGGER.info("{} replay leases: {} granted, {} reclaimed from stopped renderers", name, nowGrants, nowReclaims);
            if (nowMaxWait != maxWaitFrames && nowMaxWait > LiveRenderer.FULL_RECOVERY_FRAMES)
                GrooveMod.LOGGER.warn("{} replay wait reached {} frames, longer than one full recovery ({} frames)",
                        name, nowMaxWait, LiveRenderer.FULL_RECOVERY_FRAMES);
            if (nowEvictions != evictions)
                GrooveMod.LOGGER.warn("{} replay budget evicted a live renderer ({} total); too many renderers for its slots", name, nowEvictions);
            grants = nowGrants; reclaims = nowReclaims; evictions = nowEvictions; maxWaitFrames = nowMaxWait;
        }
    }

    /** One speaker's polled link state: what it should play, if anything, and the in-flight
     *  request bookkeeping to avoid re-polling every tick. */
    private static final class SpeakerLink {
        UUID request;
        long sent;
        long generation;
        boolean compiling, refreshAgain;
        SpeakerPackets.CommittedState wire;
        volatile LiveRenderer.Timeline program, failedSchedule;
    }
}

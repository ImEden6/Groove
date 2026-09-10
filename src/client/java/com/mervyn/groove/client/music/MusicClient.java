package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.music.MusicPackets;
import groove.engine.ClockSync;
import groove.engine.LiveRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.*;

public final class MusicClient {
    private static final ThreadPoolExecutor COMPILER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "Groove graph compiler"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static UUID epoch;
    private static long revision = -1, generation, lastPing, outstandingPing;
    private static ClockSync clock = new ClockSync();
    private static LiveRenderer renderer = new LiveRenderer();
    private static LiveRenderer.Timeline program;
    private static groove.engine.SessionTimeline.Snapshot latestSnapshot;
    private static java.util.Map<groove.engine.samples.AssetRef, String> sampleStatus = java.util.Map.of();
    private static GrooveSound sound;
    private static GrooveAudioStream stream;
    private static int retryTicks;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.SubmitResult.TYPE, (packet, context) -> {
            if (context.client().screen instanceof com.mervyn.groove.client.ui.GrooveEditorScreen editor) editor.submissionResult(packet);
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.Snapshot.TYPE, (packet, context) -> {
            if (!packet.epoch().equals(epoch)) {
                reset(context.client());
                epoch = packet.epoch();
            }
            if (packet.snapshot().revision() <= revision) return;
            revision = packet.snapshot().revision();
            latestSnapshot = packet.snapshot();
            refreshSamples();
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.Pong.TYPE, (packet, context) -> {
            if (packet.sent() == outstandingPing) {
                clock.observe(packet.sent(), System.nanoTime(), packet.serverNanos());
                outstandingPing = 0;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { reset(client); COMPILER.shutdownNow(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getConnection() == null || !ClientPlayNetworking.canSend(MusicPackets.Ping.TYPE)) return;
            long now = System.nanoTime();
            if (lastPing == 0 || now - lastPing >= 1_000_000_000L) {
                lastPing = now;
                outstandingPing = now;
                ClientPlayNetworking.send(new MusicPackets.Ping(now));
            }
            if (client.level == null || program == null || !clock.ready() || client.isPaused()
                    || client.getOverlay() != null || !client.getSoundManager().getAvailableSounds().contains(GrooveMod.id("session"))) return;
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
        var snapshot = latestSnapshot;
        if (snapshot == null || COMPILER.isShutdown()) return;
        long ticket = ++generation;
        COMPILER.execute(() -> {
            try {
                var prepared = SampleLibrary.prepare(snapshot);
                Minecraft.getInstance().execute(() -> {
                    if (ticket != generation) return;
                    program = prepared.timeline(); sampleStatus = prepared.status();
                    renderer.publish(program);
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
        latestSnapshot = null; sampleStatus = java.util.Map.of();
        lastPing = 0; outstandingPing = 0;
        if (sound != null) client.getSoundManager().stop(sound);
        if (stream != null) stream.close();
        sound = null; stream = null; retryTicks = 0;
        clock = new ClockSync(); renderer = new LiveRenderer();
        COMPILER.getQueue().clear();
    }
}

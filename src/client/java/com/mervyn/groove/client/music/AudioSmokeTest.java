package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import groove.engine.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Opt-in development test. Exercises Minecraft's real streaming path with a silent OpenAL driver. */
public final class AudioSmokeTest {
    private static GrooveAudioStream stream;
    private static GrooveSound sound;
    private static LiveRenderer renderer;
    private static LiveRenderer.Timeline timeline;
    private static long began;
    private static boolean complete;
    private static boolean stalled, stopping;
    private static boolean signals;
    private static long stoppedAt;
    public static void register() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment() || !Boolean.getBoolean("groove.audioSmoke")) return;
        began = System.nanoTime();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (complete) return;
            if (timeline != null) timeline.prepare(System.nanoTime());
            if (System.nanoTime() - began > 90_000_000_000L) throw new IllegalStateException("GROOVE AUDIO SMOKE FAILED: timeout; stream="
                    + (stream == null ? "null" : stream.reads() + " reads; closed=" + stream.closed())
                    + "; overlay=" + client.getOverlay());
            if (stream == null && client.getOverlay() == null
                    && client.getSoundManager().getAvailableSounds().contains(GrooveMod.id("session"))) {
                long now = System.nanoTime();
                try (var ogg = client.getResourceManager().getResourceOrThrow(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("sounds/random/click.ogg")).open()) {
                    var decoded = SampleDecoder.decode(ogg.readNBytes(groove.engine.samples.SampleData.MAX_BYTES + 1));
                    if (decoded.frames() <= 0) throw new IllegalStateException("Empty OGG fixture");
                    GrooveMod.LOGGER.info("Groove Vorbis decode passed: {} frames", decoded.frames());
                } catch (java.io.IOException error) { throw new IllegalStateException("Missing OGG smoke fixture", error); }
                ClockSync clock = new ClockSync(); clock.observe(now, now, now);
                renderer = new LiveRenderer();
                var prepared = SampleLibrary.prepare(new SessionTimeline.Snapshot(
                        new SessionState(1, now - 100_000_000L, 0, 128, true, groove.engine.samples.FactorySamples.demo()), null));
                if (!prepared.needed().isEmpty()) throw new IllegalStateException("Factory samples unresolved");
                timeline = prepared.timeline();
                renderer.publish(timeline);
                stream = new GrooveAudioStream(renderer, clock);
                sound = new GrooveSound(stream);
                client.getSoundManager().play(sound);
                GrooveMod.LOGGER.info("Groove audio smoke stream started");
            }
            if (stream != null && stream.reads() >= 8 && !stalled) {
                stalled = true;
                stream.stallNextReadForTest();
            }
            if (stream != null && stream.reads() >= 128 && !signals) {
                long now = System.nanoTime();
                Graph graph = SignalGraph.assignBirths(SignalDemo.graph(),null,now);
                timeline = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(new SessionState(2,now,0,128,true,graph),null));
                timeline.prepare(now); renderer.publish(timeline); signals = true;
                GrooveMod.LOGGER.info("Groove audio smoke switched to modulation and feedback routing");
            }
            if (stream != null && stream.reads() >= 256 && !stopping) {
                if (stream.maxQueuedFrames() == 0 || stream.peak() < .01 || stream.closed())
                    throw new IllegalStateException("GROOVE AUDIO SMOKE FAILED: missing queue timing or PCM");
                if (renderer.scheduleMisses() != 0) throw new IllegalStateException("Lookahead starved during smoke test");
                if (stream.recoveries() == 0) throw new IllegalStateException("Underrun recovery was not exercised");
                client.getSoundManager().stop(sound);
                stopping = true; stoppedAt = System.nanoTime();
            }
            if (stopping && stream.closed()) {
                GrooveMod.LOGGER.info("GROOVE AUDIO SMOKE PASSED: {} reads, {} underrun recoveries; explicit stop closed the stream",
                        stream.reads(), stream.recoveries());
                complete = true;
                client.stop();
            }
            if (stopping && System.nanoTime() - stoppedAt > 3_000_000_000L) throw new IllegalStateException("Explicit stop failed to close stream");
        });
    }
}

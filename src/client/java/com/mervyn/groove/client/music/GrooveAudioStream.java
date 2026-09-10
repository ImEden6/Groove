package com.mervyn.groove.client.music;

import groove.engine.ClockSync;
import groove.engine.LiveRenderer;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM ownership transfers to Minecraft SoundBuffer, which frees the native allocation. */
public final class GrooveAudioStream implements AudioStream {
    public static final int CHUNK_FRAMES = 2048;
    private static final AudioFormat FORMAT = new AudioFormat(LiveRenderer.SAMPLE_RATE, 16, 2, true, false);
    private final LiveRenderer renderer;
    private final ClockSync clock;
    private final float[] samples = new float[CHUNK_FRAMES * 2];
    private volatile boolean closed;
    private volatile long reads, maxQueuedFrames;
    private volatile double peak;
    private volatile long recoveries;
    private volatile boolean stallNextRead;
    public GrooveAudioStream(LiveRenderer renderer, ClockSync clock) { this.renderer = renderer; this.clock = clock; }
    public AudioFormat getFormat() { return FORMAT; }
    public boolean closed() { return closed; }
    public long reads() { return reads; }
    public long maxQueuedFrames() { return maxQueuedFrames; }
    public double peak() { return peak; }
    public long recoveries() { return recoveries; }
    public void recoveredUnderrun() { recoveries++; renderer.resynchronize(); }
    void stallNextReadForTest() { stallNextRead = true; }
    public ByteBuffer read(int bytes) { return readQueued(bytes, 0); }
    public ByteBuffer readQueued(int bytes, long queuedFrames) {
        if (closed || bytes < 4) return null;
        if (stallNextRead) {
            stallNextRead = false;
            // The sound executor can unpark its worker; a single park may return
            // immediately, so hold the test stall until its actual deadline.
            long deadline = System.nanoTime() + 350_000_000L;
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0)
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
        int frames = Math.min(CHUNK_FRAMES, bytes / 4);
        long target = clock.serverTime(System.nanoTime()) + Math.round(queuedFrames * 1e9 / LiveRenderer.SAMPLE_RATE);
        renderer.render(samples, frames, target);
        maxQueuedFrames = Math.max(maxQueuedFrames, queuedFrames);
        ByteBuffer pcm = MemoryUtil.memAlloc(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        double blockPeak = peak;
        for (int i = 0; i < frames * 2; i++) {
            blockPeak = Math.max(blockPeak, Math.abs(samples[i]));
            pcm.putShort((short) Math.round(samples[i] * 32767));
        }
        peak = blockPeak;
        reads++;
        return pcm.flip();
    }
    public void close() { closed = true; }
}

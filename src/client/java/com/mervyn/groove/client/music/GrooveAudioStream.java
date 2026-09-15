package com.mervyn.groove.client.music;

import groove.engine.Biquad;
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
    /** ~300ms linear fade window used when a speaker emitter stops instead of cutting instantly. */
    public static final int FADE_FRAMES = LiveRenderer.SAMPLE_RATE * 3 / 10;
    private static final AudioFormat STEREO_FORMAT = new AudioFormat(LiveRenderer.SAMPLE_RATE, 16, 2, true, false);
    private static final AudioFormat MONO_FORMAT = new AudioFormat(LiveRenderer.SAMPLE_RATE, 16, 1, true, false);
    private final LiveRenderer renderer;
    private final ClockSync clock;
    private final boolean mono;
    private final float[] samples = new float[CHUNK_FRAMES * 2];
    private volatile boolean closed;
    private volatile long reads, maxQueuedFrames;
    private volatile double peak;
    private volatile long recoveries;
    private volatile boolean stallNextRead;
    // The control thread publishes one request; only the audio thread owns progress.
    private final java.util.concurrent.atomic.AtomicInteger fadeRequest = new java.util.concurrent.atomic.AtomicInteger(-1);
    private int fadeFrames = -1, fadeRemaining;
    private static final double MUFFLE_CUTOFF_HZ = 700;
    private final Biquad muffleLeft = new Biquad(), muffleRight = new Biquad();
    private volatile boolean underwater;
    private boolean muffleActive;
    public GrooveAudioStream(LiveRenderer renderer, ClockSync clock) { this(renderer, clock, false); }
    public GrooveAudioStream(LiveRenderer renderer, ClockSync clock, boolean mono) {
        this.renderer = renderer; this.clock = clock; this.mono = mono;
    }
    public AudioFormat getFormat() { return mono ? MONO_FORMAT : STEREO_FORMAT; }
    public boolean closed() { return closed; }
    public long reads() { return reads; }
    public long maxQueuedFrames() { return maxQueuedFrames; }
    public double peak() { return peak; }
    public long recoveries() { return recoveries; }
    public void recoveredUnderrun() { recoveries++; renderer.resynchronize(); }
    void stallNextReadForTest() { stallNextRead = true; }
    /** Arms a linear fade to silence over the next {@code frames} frames, then closes the
     *  stream so playback ends naturally instead of being cut instantly. Idempotent. */
    public void fadeOut(int frames) {
        if (frames < 1) throw new IllegalArgumentException("Fade must contain at least one frame");
        fadeRequest.compareAndSet(-1, frames);
    }
    /** Headphones sealed over your ears are still muffled by your own head being underwater,
     *  regardless of how private the feed is. Read by the audio thread on the next block. */
    public void setUnderwater(boolean value) { underwater = value; }
    public ByteBuffer read(int bytes) { return readQueued(bytes, 0); }
    public ByteBuffer readQueued(int bytes, long queuedFrames) {
        if (closed || bytes < (mono ? 2 : 4)) return null;
        if (stallNextRead) {
            stallNextRead = false;
            // The sound executor can unpark its worker; a single park may return
            // immediately, so hold the test stall until its actual deadline.
            long deadline = System.nanoTime() + 350_000_000L;
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0)
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
        int frames = Math.min(CHUNK_FRAMES, bytes / (mono ? 2 : 4));
        long target = clock.serverTime(System.nanoTime()) + Math.round(queuedFrames * 1e9 / LiveRenderer.SAMPLE_RATE);
        renderer.render(samples, frames, target);
        maxQueuedFrames = Math.max(maxQueuedFrames, queuedFrames);
        ByteBuffer pcm = MemoryUtil.memAlloc(frames * (mono ? 2 : 4)).order(ByteOrder.LITTLE_ENDIAN);
        double blockPeak = peak;
        int requestedFade = fadeRequest.get();
        if (fadeFrames < 0 && requestedFade > 0) { fadeFrames = requestedFade; fadeRemaining = requestedFade; }
        boolean wantMuffle = underwater;
        if (wantMuffle != muffleActive) {
            muffleLeft.reset(); muffleRight.reset();
            if (wantMuffle) { muffleLeft.setLowPass(MUFFLE_CUTOFF_HZ, Biquad.DEFAULT_Q, LiveRenderer.SAMPLE_RATE); muffleRight.setLowPass(MUFFLE_CUTOFF_HZ, Biquad.DEFAULT_Q, LiveRenderer.SAMPLE_RATE); }
            muffleActive = wantMuffle;
        }
        for (int frame = 0; frame < frames; frame++) {
            float left = samples[frame * 2], right = samples[frame * 2 + 1];
            if (muffleActive) { left = (float) muffleLeft.process(left); right = (float) muffleRight.process(right); }
            // A low-pass step response can overshoot the renderer's limiter.
            left = Math.clamp(left, -1f, 1f); right = Math.clamp(right, -1f, 1f);
            // Fade the filtered output so its state cannot ring past the silent endpoint.
            if (fadeFrames > 0) {
                float gain = fadeFrames == 1 ? 0 : Math.max(0f, (float) (fadeRemaining - 1) / (fadeFrames - 1));
                left *= gain; right *= gain;
                if (fadeRemaining > 0) fadeRemaining--;
                if (fadeRemaining == 0) closed = true;
            }
            if (mono) {
                float mixed = (left + right) * .5f;
                blockPeak = Math.max(blockPeak, Math.abs(mixed));
                pcm.putShort((short) Math.round(mixed * 32767));
            } else {
                blockPeak = Math.max(blockPeak, Math.max(Math.abs(left), Math.abs(right)));
                pcm.putShort((short) Math.round(left * 32767));
                pcm.putShort((short) Math.round(right * 32767));
            }
        }
        peak = blockPeak;
        reads++;
        return pcm.flip();
    }
    public void close() { closed = true; }
}

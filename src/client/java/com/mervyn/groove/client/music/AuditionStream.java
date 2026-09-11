package com.mervyn.groove.client.music;

import groove.engine.samples.*;
import net.minecraft.client.sounds.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/** Independent dry preview, never routed through the shared transport or network. */
public final class AuditionStream implements AudioStream {
    private final SampleData sample;
    private final SampleVoice voice;
    private long frame;
    private volatile boolean closed;
    public AuditionStream(AssetRef ref, SampleData sample) { this.sample = sample; voice = new SampleVoice(ref, 1, .8, 0); }
    public AudioFormat getFormat() { return new AudioFormat(48000, 16, 2, true, false); }
    public ByteBuffer read(int bytes) {
        long remaining = (long) Math.ceil(sample.duration() * 48000) - frame;
        if (closed || remaining <= 0 || bytes < 4) return null;
        int frames = (int) Math.min(remaining, Math.min(2048, bytes / 4));
        ByteBuffer buffer = MemoryUtil.memAlloc(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++, frame++) for (int c = 0; c < 2; c++)
            buffer.putShort((short) Math.round(voice.value(sample, frame / 48000.0, c, 48000) * 32767));
        return buffer.flip();
    }
    public void close() { closed = true; }
}

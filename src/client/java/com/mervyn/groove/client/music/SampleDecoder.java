package com.mervyn.groove.client.music;

import groove.engine.samples.SampleData;
import groove.engine.samples.WavDecoder;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Ogg Vorbis (not Opus) via the STB already shipped with Minecraft. */
public final class SampleDecoder {
    public static SampleData decode(byte[] bytes) {
        if (bytes.length < 4 || bytes.length > SampleData.MAX_BYTES) throw new IllegalArgumentException("Invalid encoded sample size");
        if (bytes[0] == 'R' && bytes[1] == 'I') return WavDecoder.decode(bytes);
        if (bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S')
            throw new IllegalArgumentException("Expected WAV or Ogg Vorbis");
        var input = MemoryUtil.memAlloc(bytes.length); long handle = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            input.put(bytes).flip();
            var error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(input, error, null);
            if (handle == 0) throw new IllegalArgumentException("Invalid Ogg Vorbis stream: " + error.get(0));
            STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(handle, STBVorbisInfo.malloc(stack));
            int channels = info.channels(), rate = info.sample_rate();
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
            SampleData.validate(rate, channels, (long) frames * channels);
            var pcm = MemoryUtil.memAllocFloat(frames * channels);
            try {
                int actual = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, pcm);
                if (actual != frames) throw new IllegalArgumentException("Truncated Vorbis stream");
                float[] samples = new float[frames * channels]; pcm.get(samples);
                for (int i = 0; i < samples.length; i++) {
                    if (!Float.isFinite(samples[i])) throw new IllegalArgumentException("Non-finite Vorbis sample");
                    samples[i] = Math.max(-1, Math.min(1, samples[i]));
                }
                return new SampleData(rate, channels, samples);
            } finally { MemoryUtil.memFree(pcm); }
        } finally {
            if (handle != 0) STBVorbis.stb_vorbis_close(handle);
            MemoryUtil.memFree(input);
        }
    }
}

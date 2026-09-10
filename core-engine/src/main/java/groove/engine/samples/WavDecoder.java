package groove.engine.samples;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** RIFF/WAVE PCM 8/16/24/32-bit or IEEE float32; compressed and extensible WAV fail explicitly. */
public final class WavDecoder {
    public static SampleData decode(byte[] encoded) {
        if (encoded.length < 44 || encoded.length > SampleData.MAX_BYTES) throw new IllegalArgumentException("Invalid WAV length");
        ByteBuffer b = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IllegalArgumentException("Not a RIFF WAVE file");
        long riffEnd = Integer.toUnsignedLong(b.getInt(4)) + 8;
        if (riffEnd != encoded.length) throw new IllegalArgumentException("Truncated or trailing WAV data");
        int format = 0, channels = 0, rate = 0, bits = 0, align = 0, data = -1, length = 0;
        for (int offset = 12; offset + 8L <= riffEnd;) {
            int tag = b.getInt(offset);
            long size = Integer.toUnsignedLong(b.getInt(offset + 4));
            long end = offset + 8L + size;
            if (end > riffEnd) throw new IllegalArgumentException("Truncated WAV chunk");
            if (tag == 0x20746d66) {
                if (size < 16 || format != 0) throw new IllegalArgumentException("Invalid WAV format chunk");
                format = Short.toUnsignedInt(b.getShort(offset + 8));
                channels = Short.toUnsignedInt(b.getShort(offset + 10)); rate = b.getInt(offset + 12);
                align = Short.toUnsignedInt(b.getShort(offset + 20)); bits = Short.toUnsignedInt(b.getShort(offset + 22));
            } else if (tag == 0x61746164) {
                if (data >= 0) throw new IllegalArgumentException("Multiple WAV data chunks");
                data = offset + 8; length = (int) size;
            }
            offset = (int) (end + (size & 1));
        }
        if (data < 0 || !(format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32) || format == 3 && bits == 32)
                || channels < 1 || channels > 2 || align != channels * (bits / 8) || length % align != 0)
            throw new IllegalArgumentException("Unsupported WAV encoding or block alignment");
        int count = length / (bits / 8);
        SampleData.validate(rate, channels, count);
        float[] pcm = new float[count]; b.position(data);
        for (int i = 0; i < count; i++) {
            float value;
            if (format == 3) value = b.getFloat();
            else value = switch (bits) {
                case 8 -> (Byte.toUnsignedInt(b.get()) - 128) / 128f;
                case 16 -> b.getShort() / 32768f;
                case 24 -> { int n = Byte.toUnsignedInt(b.get()) | Byte.toUnsignedInt(b.get()) << 8 | b.get() << 16; yield n / 8388608f; }
                default -> (float) (b.getInt() / 2147483648.0);
            };
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite WAV sample");
            pcm[i] = Math.max(-1, Math.min(1, value));
        }
        return new SampleData(rate, channels, pcm);
    }
}

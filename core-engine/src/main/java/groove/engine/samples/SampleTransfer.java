package groove.engine.samples;

import java.io.ByteArrayOutputStream;

/** Strict sequential assembly with final content verification; one in-flight asset per client. */
public final class SampleTransfer {
    public static final int CHUNK_BYTES = 49152;
    private final AssetRef ref;
    private final int total;
    private final ByteArrayOutputStream bytes;
    public SampleTransfer(AssetRef ref, int total) {
        if (total < 1 || total > SampleData.MAX_BYTES) throw new IllegalArgumentException("Invalid transfer length");
        this.ref = ref; this.total = total; bytes = new ByteArrayOutputStream(total);
    }
    public int offset() { return bytes.size(); }
    public int total() { return total; }
    public void append(int offset, byte[] chunk) {
        if (offset != bytes.size() || chunk.length < 1 || chunk.length > CHUNK_BYTES || offset + chunk.length > total)
            throw new IllegalArgumentException("Out-of-order or oversized sample chunk");
        bytes.writeBytes(chunk);
    }
    public boolean complete() { return bytes.size() == total; }
    public byte[] finish() {
        if (!complete()) throw new IllegalArgumentException("Incomplete sample");
        byte[] data = bytes.toByteArray();
        if (!AssetRef.hash(data).equals(ref.sha256())) throw new IllegalArgumentException("Sample SHA-256 mismatch");
        return data;
    }
}

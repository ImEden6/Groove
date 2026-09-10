package groove.engine.samples;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Hashes refer to encoded file bytes, not decoder-dependent PCM. */
public record AssetRef(String assetId, String sha256) {
    public AssetRef {
        if (assetId == null || assetId.length() > 160 || !assetId.matches("[a-z0-9_-]+:[a-z0-9_./-]+")
                || assetId.contains("..") || assetId.contains("//") || assetId.endsWith("/")
                || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected namespaced asset ID and full lowercase SHA-256");
    }
    public static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

package groove.engine;

import groove.engine.samples.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class SampleTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        String id = "factory:basic/kick.wav";
        byte[] encoded = FactorySamples.bytes(id);
        AssetRef ref = FactorySamples.ref(id);
        check(ref.sha256().equals(AssetRef.hash(encoded)), "Factory hash matches exact bytes");
        SampleData kick = WavDecoder.decode(encoded);
        check(kick.rate() == 24000 && kick.channels() == 1 && kick.frames() == 14400, "Factory WAV metadata");
        for (int bits : new int[] {8, 16, 24, 32}) {
            var b = ByteBuffer.allocate(44 + bits / 8 * 2).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0x46464952).putInt(b.capacity() - 8).putInt(0x45564157).putInt(0x20746d66).putInt(16);
            b.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(8000 * bits / 8).putShort((short) (bits / 8)).putShort((short) bits);
            b.putInt(0x61746164).putInt(bits / 8 * 2);
            // Most-negative then zero amplitude in every supported integer encoding.
            for (int j = 0; j < bits / 8; j++) b.put((byte) (bits == 8 ? 0 : j == bits / 8 - 1 ? 128 : 0));
            for (int j = 0; j < bits / 8; j++) b.put((byte) (bits == 8 ? 128 : 0));
            SampleData decoded = WavDecoder.decode(b.array());
            check(decoded.at(0, 0) == -1 && decoded.at(1, 0) == 0, "PCM " + bits + " signed conversion");
        }
        invalid(() -> WavDecoder.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] invalidWav = encoded.clone(); invalidWav[20] = 6;
        invalid(() -> WavDecoder.decode(invalidWav));
        float[] source = {0, 1, 0, -1}; SampleData immutable = new SampleData(8000, 1, source); source[1] = 0;
        check(immutable.at(.5, 0) == .5f, "Linear interpolation and ownership");
        check(immutable.at(-1, 0) == 0 && immutable.at(4, 0) == 0, "Out-of-range playback is silent");
        check(Arrays.equals(immutable.peaks(2), new float[]{1, 1}), "Waveform peaks");
        invalid(() -> new AssetRef("custom:../private.wav", ref.sha256()));
        invalid(() -> new AssetRef("custom:kit/snare.wav", "1234"));
        invalid(() -> new SampleData(8000, 1, new float[]{Float.NaN}));
        invalid(() -> SampleData.validate(8000, 1, 80001));
        SampleVoice fast = new SampleVoice(ref, 2, 1, 0), normal = new SampleVoice(ref, 1, 1, 0);
        check(Math.abs(fast.value(kick, .05, 0) - normal.value(kick, .1, 0)) < 1e-6, "Pitch scales playback position");
        check(fast.value(kick, .31, 0) == 0, "Pitched sample finishes sooner");
        SampleCache cache = new SampleCache(immutable.bytes()); cache.put("a", immutable); cache.put("b", immutable);
        check(cache.get("a") == null && cache.get("b") == immutable && cache.bytes() == immutable.bytes(), "LRU respects byte budget");
        check(immutable.at(.5, 0) == .5, "Eviction does not invalidate pinned audio");

        Path folder = Files.createTempDirectory("groove-sample-test-"); Path file = folder.resolve("kick.wav");
        try {
            Files.write(file, encoded);
            SampleCatalog catalog = SampleCatalog.scan(folder);
            AssetRef custom = catalog.find("custom:kick.wav").ref();
            check(catalog.status(custom) == SampleCatalog.Status.READY, "Catalog resolves exact custom sample");
            byte[] changed = encoded.clone(); changed[50] ^= 3; Files.write(file, changed);
            check(SampleCatalog.scan(folder).status(custom) == SampleCatalog.Status.HASH_MISMATCH, "Hot reload detects content mutation");
            Files.delete(file);
            check(SampleCatalog.scan(folder).status(custom) == SampleCatalog.Status.MISSING, "Hot reload detects deletion");
        } finally { Files.deleteIfExists(file); Files.delete(folder); }
        SampleTransfer transfer = new SampleTransfer(ref, encoded.length);
        transfer.append(0, encoded);
        check(Arrays.equals(transfer.finish(), encoded), "Verified transfer round trip");
        invalid(() -> new SampleTransfer(ref, SampleData.MAX_BYTES + 1));
        invalid(() -> new SampleTransfer(ref, encoded.length).append(1, encoded));
        SampleTransfer corrupt = new SampleTransfer(ref, encoded.length); byte[] damaged = encoded.clone(); damaged[50] ^= 1;
        corrupt.append(0, damaged); invalid(corrupt::finish);

        Graph graph = new Graph(2, List.of(new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(), ref),
                new Graph.Node("step", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 1.0)), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("sample", "step"), Graph.edge("step", "out")));
        LoopPlan plan = GraphCompiler.compile(graph);
        check(plan.size() == 1 && plan.event(0).sample().asset().equals(ref), "Graph transforms preserve asset identity");
        long start = 1_000_000_000L;
        var state = new SessionState(1, start, 0, 120, true, graph);
        LiveRenderer loaded = new LiveRenderer(); loaded.publish(new LiveRenderer.Timeline(new LiveRenderer.Program(state, plan, Map.of(ref, kick)), null));
        float[] output = new float[1024]; loaded.render(output, 512, start + 300_000_000L);
        double energy = 0; for (float v : output) energy += v * v;
        check(energy > .001, "One-shot tail survives beyond Euclidean step");
        LiveRenderer missing = new LiveRenderer(); missing.publish(new LiveRenderer.Timeline(new LiveRenderer.Program(state, plan), null));
        missing.render(output, 512, start + 300_000_000L);
        check(Arrays.equals(output, new float[1024]), "Missing bank entry is silent");
        var loadedTimeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, plan, Map.of(ref, kick)), null);
        loaded.resynchronize(); loaded.render(output, 512, start + 2_300_000_000L);
        LiveRenderer rejoined = new LiveRenderer(); rejoined.publish(loadedTimeline);
        float[] fresh = new float[1024]; rejoined.render(fresh, 512, start + 2_300_000_000L);
        check(Arrays.equals(output, fresh), "Underrun rebase restores phase and fade like a fresh join");
        check(GraphCompiler.compile(FactorySamples.demo()).size() == 11, "Sample demo compiles");
        System.out.println("Passed " + checks + " sample engine/catalog/transfer checks.");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
}

package com.mervyn.groove.music;

import com.mervyn.groove.client.ui.SampleWarnings;
import groove.engine.Graph;
import groove.engine.NodeType;
import groove.engine.samples.AssetRef;
import groove.engine.samples.SampleCatalog;
import java.util.Map;

final class SampleWarningTests {
    static void run() throws Exception {
        SampleCatalog catalog = SampleCatalog.scan(null);
        AssetRef ready = catalog.entries().getFirst().ref();
        AssetRef mismatched = new AssetRef(ready.assetId(), "0".repeat(64));
        AssetRef missing = new AssetRef("custom:no/such_sample.ogg", "0".repeat(64));
        check(SampleWarnings.of(sample(ready), catalog) == null, "Ready sample has no warning");
        check("Sample doesn't match the server copy".equals(SampleWarnings.of(sample(mismatched), catalog)), "Wrong hash warns as mismatch");
        check("Sample missing".equals(SampleWarnings.of(sample(missing), catalog)), "Unknown asset warns as missing");
        check(SampleWarnings.of(new Graph.Node("tone", NodeType.TONE, Map.of()), catalog) == null, "Tone node has no warning");
        System.out.println("Sample warning checks passed.");
    }
    private static Graph.Node sample(AssetRef ref) { return new Graph.Node("s", NodeType.GENERATOR_SAMPLE, Map.of(), ref); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

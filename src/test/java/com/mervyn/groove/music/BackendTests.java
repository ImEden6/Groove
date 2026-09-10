package com.mervyn.groove.music;

import groove.engine.*;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import java.util.UUID;

public final class BackendTests {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        persistenceChecks();
        typedCompatibilityChecks();
        var searchCache = new com.mervyn.groove.client.ui.SampleSearch();
        var catalog = groove.engine.samples.SampleCatalog.scan(null);
        var rows = searchCache.filter(catalog, "@factory");
        check(!rows.isEmpty() && rows == searchCache.filter(catalog, "@factory"), "Unchanged sample query reuses cached list");
        check(searchCache.filter(catalog, "no_such_sample").isEmpty(), "Search changes invalidate sample results");
        var refreshed = groove.engine.samples.SampleCatalog.scan(null);
        check(!searchCache.filter(refreshed, "@factory").isEmpty(), "Catalog replacement refreshes results");
        var saves = MusicServer.newSaveExecutor();
        var order = new java.util.ArrayList<Integer>();
        String callerThread = Thread.currentThread().getName();
        try {
            var firstSave = saves.submit(() -> { order.add(1); return Thread.currentThread().getName(); });
            var secondSave = saves.submit(() -> order.add(2));
            check(!firstSave.get(5, java.util.concurrent.TimeUnit.SECONDS).equals(callerThread), "Saves execute off caller thread");
            secondSave.get(5, java.util.concurrent.TimeUnit.SECONDS);
            check(order.equals(java.util.List.of(1, 2)), "Saves retain submission order");
        } finally { saves.shutdownNow(); }
        Graph graph = Graph.demo();
        check(GraphJson.decode(GraphJson.encode(graph)).equals(graph), "Graph JSON round trip");
        Graph sampleGraph = groove.engine.samples.FactorySamples.demo();
        editorChecks(sampleGraph);
        check(GraphJson.decode(GraphJson.encode(sampleGraph)).equals(sampleGraph), "Sample reference JSON round trip");
        Graph filteredGraph = new Graph(1, java.util.List.of(
                new Graph.Node("lead", NodeType.TONE, java.util.Map.of(NodeParam.FREQUENCY, 440.0, NodeParam.CUTOFF_HZ, 733.0)),
                new Graph.Node("out", NodeType.OUTPUT, java.util.Map.of())),
                java.util.List.of(Graph.edge("lead", "out")));
        Graph decodedFiltered = GraphJson.decode(GraphJson.encode(filteredGraph));
        check(decodedFiltered.equals(filteredGraph), "Custom cutoffHz JSON round trip");
        check(decodedFiltered.nodes().get(0).params().get(NodeParam.CUTOFF_HZ) == 733.0, "cutoffHz value survives encode/decode exactly");
        invalid(() -> GraphJson.decode("null"));
        invalid(() -> GraphJson.decode("[".repeat(17) + "]".repeat(17)));
        invalid(() -> GraphJson.decode(" ".repeat(GraphJson.MAX_LENGTH + 1)));
        invalid(() -> GraphJson.decode("{\"version\":1,\"nodes\":[],\"edges\":[]}"));
        SessionTimeline timeline = new SessionTimeline(graph, 128, 3_000_000_000L);
        var state = timeline.schedule(graph, 128, true, 0, 3_000_000_000L);
        MusicPackets.Snapshot original = new MusicPackets.Snapshot(UUID.randomUUID(), state);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MusicPackets.Snapshot.CODEC.encode(buf, original);
            check(buf.readableBytes() < 65536, "Snapshot fits packet budget");
            check(MusicPackets.Snapshot.CODEC.decode(buf).equals(original), "Snapshot wire round trip");
            check(buf.readableBytes() == 0, "Decoder consumes packet exactly");
            buf.clear();
            check(original.snapshot().equals(state), "Worker snapshot decoding preserves timeline");
            var malformed = new MusicPackets.Snapshot(UUID.randomUUID(),
                    new MusicPackets.WireState(0, 0, 0, 128, false, "not JSON"), null);
            MusicPackets.Snapshot.CODEC.encode(buf, malformed);
            var raw = MusicPackets.Snapshot.CODEC.decode(buf);
            check(raw.equals(malformed), "Wire codec leaves JSON unparsed");
            invalid(raw::snapshot);
            buf.clear();
            var submit = new MusicPackets.Submit(original.epoch(), UUID.randomUUID(), 0, GraphJson.encode(sampleGraph), 128, true);
            MusicPackets.Submit.CODEC.encode(buf, submit);
            check(MusicPackets.Submit.CODEC.decode(buf).equals(submit), "Patch submission wire round trip");
            check(buf.readableBytes() == 0, "Submission consumes packet exactly");
            buf.clear();
            var result = new MusicPackets.SubmitResult(submit.request(), false, "Stale revision");
            MusicPackets.SubmitResult.CODEC.encode(buf, result);
            check(MusicPackets.SubmitResult.CODEC.decode(buf).equals(result), "Submission rejection round trip");
            buf.clear();
            MusicPackets.Ping.CODEC.encode(buf, new MusicPackets.Ping(456));
            check(MusicPackets.Ping.CODEC.decode(buf).sent() == 456, "Ping round trip");
            buf.clear();
            MusicPackets.Pong.CODEC.encode(buf, new MusicPackets.Pong(456, 999));
            check(MusicPackets.Pong.CODEC.decode(buf).equals(new MusicPackets.Pong(456, 999)), "Pong round trip");
            buf.clear();
            var ref = groove.engine.samples.FactorySamples.ref("factory:basic/kick.wav");
            var request = new MusicPackets.AssetRequest(ref, 0);
            MusicPackets.AssetRequest.CODEC.encode(buf, request);
            check(MusicPackets.AssetRequest.CODEC.decode(buf).equals(request), "Asset request round trip");
            buf.clear();
            byte[] data = groove.engine.samples.FactorySamples.bytes(ref.assetId());
            MusicPackets.AssetChunk.CODEC.encode(buf, new MusicPackets.AssetChunk(ref, 0, data.length, data));
            var chunk = MusicPackets.AssetChunk.CODEC.decode(buf);
            check(chunk.ref().equals(ref) && chunk.total() == data.length && java.util.Arrays.equals(chunk.data(), data), "Asset chunk wire round trip");
            check(buf.readableBytes() == 0, "Asset decoder consumes packet exactly");
            invalid(() -> new MusicPackets.AssetChunk(ref, 0, 0, data));
            invalid(() -> new MusicPackets.AssetRequest(ref, -1));
        } finally { buf.release(); }
        System.out.println("Passed graph JSON and Minecraft packet checks.");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void editorChecks(Graph graph) {
        var editor = new com.mervyn.groove.client.ui.EditorState(graph);
        var original = graph.nodes().stream().filter(n -> n.sample() != null).findFirst().orElseThrow();
        var replacement = groove.engine.samples.FactorySamples.ref("factory:basic/snare.wav");
        editor.dropSample(replacement, new com.mervyn.groove.client.ui.Vec2(250, 100), original.id());
        check(editor.node(original.id()).sample().equals(replacement), "Drop swaps sample");
        check(editor.node(original.id()).params().equals(original.params()), "Swap preserves tuning");
        check(editor.toGraph().edges().equals(graph.edges()), "Swap preserves cables");
        GraphCompiler.compile(editor.toGraph());
        editor.undo(); check(editor.toGraph().equals(graph), "Undo restores exact sample hash");
        editor.redo(); check(editor.node(original.id()).sample().equals(replacement), "Redo restores replacement");
        editor.dropSample(replacement, new com.mervyn.groove.client.ui.Vec2(250, 100), null);
        check(editor.nodes().size() == graph.nodes().size() + 1 && editor.toGraph().version() == 2, "Drop creates version 2 sample node");

        // Quick spawn tests
        editor.openQuickSpawn(new com.mervyn.groove.client.ui.Vec2(300, 200));
        check(editor.isQuickSpawnOpen(), "Quick spawn opens");
        editor.spawnNode("tone_test", NodeType.TONE);
        check(!editor.isQuickSpawnOpen() && editor.node("tone_test") != null, "Quick spawn creates tone node");
        check(editor.node("tone_test").params().get(NodeParam.FREQUENCY) == 220.0, "Tone spawned with default frequency");
        editor.beginValueDrag("tone_test", NodeParam.FREQUENCY, 100, false);
        editor.dragValueTo(5000); // 100 - 5000 = -4900 -> clamped to 20
        check(editor.node("tone_test").params().get(NodeParam.FREQUENCY) == 20.0, "Value drag clamps to minimum frequency");
        editor.dragValueTo(-20000); // clamped to 16000
        check(editor.node("tone_test").params().get(NodeParam.FREQUENCY) == 16000.0, "Value drag clamps to maximum frequency");
        editor.endValueDrag();

        // A small, non-fine drag on a wide-range param must move it by more than a
        // couple of Hz, or sweeping the full 20..16000 range is impractically slow.
        // Uses a fresh node (default frequency 220, well clear of the range clamps
        // just exercised above) so the drag isn't already pinned at a boundary.
        editor.openQuickSpawn(new com.mervyn.groove.client.ui.Vec2(320, 220));
        editor.spawnNode("sensitivity_test", NodeType.TONE);
        editor.beginValueDrag("sensitivity_test", NodeParam.FREQUENCY, 100, false);
        editor.dragValueTo(0); // 100px, no Ctrl (coarse)
        double coarseMoved = editor.node("sensitivity_test").params().get(NodeParam.FREQUENCY) - 220.0;
        check(Math.abs(coarseMoved) > 1000, "Coarse frequency drag covers a meaningful fraction of the range");
        editor.endValueDrag();
        editor.beginValueDrag("sensitivity_test", NodeParam.FREQUENCY, 100, true);
        editor.dragValueTo(0); // same 100px, Ctrl held (fine)
        double fineMoved = editor.node("sensitivity_test").params().get(NodeParam.FREQUENCY) - coarseMoved - 220.0;
        check(Math.abs(fineMoved) < Math.abs(coarseMoved), "Ctrl (fine) drag moves less than an unmodified drag");
        editor.endValueDrag();
        check(com.mervyn.groove.client.ui.EditorState.clampParam(NodeType.GENERATOR_SAMPLE, NodeParam.PITCH_RATIO, 8.0, java.util.Map.of()) == 4.0,
                "pitchRatio clamp matches the engine's actual 0.25..4 range");
        check(com.mervyn.groove.client.ui.EditorState.clampParam(NodeType.GENERATOR_SAMPLE, NodeParam.PITCH_RATIO, 0.1, java.util.Map.of()) == 0.25,
                "pitchRatio clamp matches the engine's actual 0.25..4 range (lower bound)");
        check(editor.node("tone_test").params().get(NodeParam.CUTOFF_HZ) == 20000.0, "Tone spawned with default cutoffHz");

        // A tone node from before cutoffHz existed (like a pre-existing patch's node) has no
        // "cutoffHz" key at all; the Inspector must still show and let you edit it.
        var legacyEditor = new com.mervyn.groove.client.ui.EditorState(new Graph(1,
                java.util.List.of(new Graph.Node("legacy", NodeType.TONE, java.util.Map.of(NodeParam.FREQUENCY, 440.0)),
                        new Graph.Node("out", NodeType.OUTPUT, java.util.Map.of())),
                java.util.List.of(Graph.edge("legacy", "out"))));
        var legacyDisplay = com.mervyn.groove.client.ui.EditorState.displayParams(legacyEditor.node("legacy"));
        check(legacyDisplay.containsKey(NodeParam.CUTOFF_HZ) && legacyDisplay.get(NodeParam.CUTOFF_HZ) == 20000.0,
                "Inspector shows cutoffHz default for a node that never set it");
        legacyEditor.beginValueDrag("legacy", NodeParam.CUTOFF_HZ, 100, false);
        legacyEditor.dragValueTo(-500); // starts from the 20000 default, not 0
        check(legacyEditor.node("legacy").params().get(NodeParam.CUTOFF_HZ) == 20000.0,
                "Dragging an absent param starts from its type default, not zero");
        legacyEditor.dragValueTo(50000);
        check(legacyEditor.node("legacy").params().get(NodeParam.CUTOFF_HZ) == 20.0,
                "Dragging an absent param still clamps and persists it into the node");
        legacyEditor.endValueDrag();
        editor.beginValueDrag("tone_test", NodeParam.CUTOFF_HZ, 100, false); // starts at default 20000
        editor.dragValueTo(100000); // pointer moves far down -> value falls well below 20 -> clamped
        check(editor.node("tone_test").params().get(NodeParam.CUTOFF_HZ) == 20.0, "Value drag clamps cutoffHz to minimum");
        editor.dragValueTo(-100000); // pointer moves far up -> value exceeds 20000 -> clamped
        check(editor.node("tone_test").params().get(NodeParam.CUTOFF_HZ) == 20000.0, "Value drag clamps cutoffHz to maximum");
        editor.endValueDrag();

        editor.openQuickSpawn(new com.mervyn.groove.client.ui.Vec2(400, 200));
        editor.spawnNode("euclid_test", NodeType.EUCLID);
        check(editor.node("euclid_test").params().get(NodeParam.STEPS) == 16.0, "Euclid spawned with default steps");
        check(editor.node("euclid_test").params().get(NodeParam.PULSES) == 4.0, "Euclid spawned with default pulses");
        editor.beginValueDrag("euclid_test", NodeParam.PULSES, 100, false);
        editor.dragValueTo(0); // pulses clamped to steps
        check(editor.node("euclid_test").params().get(NodeParam.PULSES) == 16.0, "Euclid pulses clamped to steps count");
        editor.endValueDrag();
        editor.beginValueDrag("euclid_test", NodeParam.STEPS, 100, false);
        editor.dragValueTo(140);
        editor.endValueDrag();
        check(editor.node("euclid_test").params().get(NodeParam.STEPS) == 4.0, "Steps shrink with parameter sensitivity");
        check(editor.node("euclid_test").params().get(NodeParam.PULSES) == 4.0, "Shrinking steps also clamps pulses");
        editor.undo();
        check(editor.node("euclid_test").params().get(NodeParam.STEPS) == 16.0
                && editor.node("euclid_test").params().get(NodeParam.PULSES) == 16.0,
                "Undo restores both steps and pulses together");

        var controller = new com.mervyn.groove.client.ui.InputController(editor);
        editor.openQuickSpawn(new com.mervyn.groove.client.ui.Vec2(500, 200));
        check(controller.handleEscape(false, false) == com.mervyn.groove.client.ui.InputController.EscapeConsumer.QUICK_SPAWN, "Escape consumes quick spawn");
        check(!editor.isQuickSpawnOpen(), "Escape closed quick spawn");
        var wireEditor = new com.mervyn.groove.client.ui.EditorState(new Graph(1,
                java.util.List.of(new Graph.Node("source", NodeType.TONE, java.util.Map.of()),
                        new Graph.Node("sink", NodeType.OUTPUT, java.util.Map.of())), java.util.List.of()));
        wireEditor.layout().place("sink", new com.mervyn.groove.client.ui.Vec2(300, 100));
        var wireInput = new com.mervyn.groove.client.ui.InputController(wireEditor);
        java.util.function.BiPredicate<Double, Double> visible = (x, y) -> x < 310;
        wireEditor.startWireDrag("source", new com.mervyn.groove.client.ui.Vec2(0, 0));
        wireInput.mouseDrag(300, 131, 0, 0, visible);
        wireInput.mouseDrag(315, 131, 15, 0, visible);
        wireInput.mouseUp();
        check(wireEditor.edges().isEmpty(), "Crossing an overlay clears the previous wire target");
        wireEditor.startWireDrag("source", new com.mervyn.groove.client.ui.Vec2(0, 0));
        wireInput.mouseDrag(300, 131, 0, 0, visible);
        wireInput.mouseUp(315, 131, visible);
        check(wireEditor.edges().isEmpty(), "Release over overlay cancels without a final drag event");
        wireEditor.startWireDrag("source", new com.mervyn.groove.client.ui.Vec2(0, 0));
        wireInput.mouseUp(295, 131, (x, y) -> x < 300);
        check(wireEditor.edges().isEmpty(), "Magnet radius cannot reach a hidden port");
        wireEditor.startWireDrag("source", new com.mervyn.groove.client.ui.Vec2(0, 0));
        wireInput.mouseDrag(315, 131, 0, 0, visible);
        wireInput.mouseUp(300, 131, visible);
        check(wireEditor.edges().size() == 1, "Returning to visible canvas allows connection");
        wireInput.startValueDrag("source", NodeParam.FREQUENCY, 100, false);
        wireInput.mouseDrag(315, 90, 0, -10, visible);
        wireInput.mouseUp(315, 90, visible);
        check(wireEditor.node("source").params().get(NodeParam.FREQUENCY) == 620.0,
                "Parameter drags continue over overlays");
        wireEditor.layout().place("source", new com.mervyn.groove.client.ui.Vec2(100, 100));
        wireInput.mouseDown(com.mervyn.groove.client.ui.InputController.Button.PRIMARY, 120, 120, false);
        wireInput.mouseDrag(320, 120, 200, 0, visible);
        wireInput.mouseUp(320, 120, visible);
        check(wireEditor.layout().get("source").x() == 300, "Node drags continue over overlays");
        var knobTone = new Graph.Node("knob", NodeType.TONE, java.util.Map.of());
        var knobBounds = new com.mervyn.groove.client.ui.RotaryKnob(NodeParam.GAIN, 10, 20, 68);
        check(knobBounds.contains(10, 20) && knobBounds.contains(77, 67), "Knob cell includes label and value");
        check(!knobBounds.contains(78, 30) && !knobBounds.contains(20, 68), "Knob cell excludes adjacent controls");
        check(com.mervyn.groove.client.ui.KnobScale.angle(knobTone, NodeParam.GAIN, 0) == -135,
                "Knob minimum points down-left");
        check(com.mervyn.groove.client.ui.KnobScale.angle(knobTone, NodeParam.GAIN, 1) == 135,
                "Knob maximum points down-right");
        check(com.mervyn.groove.client.ui.KnobScale.angle(knobTone, NodeParam.PAN, 0) == 0,
                "Centered pan points upwards");
        check(com.mervyn.groove.client.ui.KnobScale.angle(knobTone, NodeParam.GAIN, 5) == 135,
                "Knob angle clamps out-of-range values");
        var knobEuclid = new Graph.Node("rhythm", NodeType.EUCLID, java.util.Map.of(NodeParam.STEPS, 8.0));
        check(com.mervyn.groove.client.ui.KnobScale.angle(knobEuclid, NodeParam.PULSES, 8) == 135,
                "Pulses knob uses current steps as maximum");
        check(com.mervyn.groove.client.ui.EditorState.defaultParams(NodeType.GENERATOR_SAMPLE).size() == 3,
                "Samples expose knobs even when saved parameters are omitted");
        var timeline = new SessionTimeline(graph, 128, 0);
        timeline.schedule(graph, 128, true, 0, 0);
        invalid(() -> timeline.schedule(graph, 128, true, 0, 1));
        invalid(() -> timeline.schedule(graph, 128, true, 1, 1));
        check(timeline.snapshot(1).revision() == 1, "Rejected stale and pending edits preserve session");
    }
    private static void typedCompatibilityChecks() {
        // Fixed legacy wire strings, deliberately not generated with GraphJson.encode.
        String v1 = """
                {"version":1,"nodes":[
                  {"id":"tone","type":"tone","params":{"frequency":440}},
                  {"id":"fast","type":"fast","params":{"factor":2}},
                  {"id":"rhythm","type":"euclid","params":{"steps":8,"pulses":3}},
                  {"id":"mix","type":"stack","params":{}},
                  {"id":"out","type":"output","params":{}}],
                 "edges":[
                  {"fromNode":"tone","fromPort":"out","toNode":"fast","toPort":"in"},
                  {"fromNode":"fast","fromPort":"out","toNode":"rhythm","toPort":"in"},
                  {"fromNode":"rhythm","fromPort":"out","toNode":"mix","toPort":"in"},
                  {"fromNode":"mix","fromPort":"out","toNode":"out","toPort":"in"}]}
                """;
        String v2 = """
                {"version":2,"nodes":[
                  {"id":"sample","type":"generator/sample","params":{"pitchRatio":1,"gain":0.8,"pan":0},
                   "sample":{"assetId":"custom:test/kick","sha256":"0000000000000000000000000000000000000000000000000000000000000000"}},
                  {"id":"out","type":"output","params":{}}],
                 "edges":[{"fromNode":"sample","fromPort":"out","toNode":"out","toPort":"in"}]}
                """;
        for (String fixture : java.util.List.of(v1, v2)) {
            Graph decoded = GraphJson.decode(fixture);
            var expectedNodes = com.google.gson.JsonParser.parseString(fixture).getAsJsonObject().getAsJsonArray("nodes");
            var actualNodes = com.google.gson.JsonParser.parseString(GraphJson.encode(decoded)).getAsJsonObject().getAsJsonArray("nodes");
            for (int i = 0; i < expectedNodes.size(); i++)
                check(expectedNodes.get(i).getAsJsonObject().get("type").equals(actualNodes.get(i).getAsJsonObject().get("type")),
                        "Legacy node spelling survives typed serialization");
            check(GraphJson.decode(GraphJson.encode(decoded)).equals(decoded), "Legacy fixture graph survives round trip");
        }
        check(GraphJson.decode(v2).nodes().getFirst().type() == NodeType.GENERATOR_SAMPLE, "Legacy sample maps to enum");
        invalid(() -> GraphJson.decode(v1.replace("\"type\":\"tone\"", "\"type\":\"unknown\"")));
        invalid(() -> GraphJson.decode(v1.replace("\"type\":\"tone\"", "\"type\":null")));
        var locale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            check(NodeType.EUCLID.idStem().equals("euclid"), "Quick spawn ID stays ASCII under Turkish locale");
            for (NodeType type : NodeType.values())
                check((type.idStem() + "_1").matches("[a-zA-Z0-9_-]{1,32}"), "Every generated node ID remains valid");
        } finally { java.util.Locale.setDefault(locale); }
    }
    private static void persistenceChecks() throws Exception {
        var root = java.nio.file.Files.createTempDirectory("groove-save-test-");
        try {
            var original = new SessionStore.Saved(Graph.demo(), 123);
            java.nio.file.Files.writeString(root.resolve("groove-patch.json"), GraphJson.encode(original.graph()));
            java.nio.file.Files.writeString(root.resolve("groove-tempo.txt"), "123");
            check(SessionStore.read(root).equals(original), "Legacy patch and tempo load together");
            SessionStore.write(root, original);
            var next = new SessionStore.Saved(groove.engine.samples.FactorySamples.demo(), 177);
            try {
                SessionStore.write(root, next, (from, to) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(from.toString(), to.toString(), "test failure");
                });
                throw new AssertionError("Expected failed commit");
            } catch (java.nio.file.AtomicMoveNotSupportedException expected) { }
            check(SessionStore.read(root).equals(original), "Failed commit preserves both old graph and tempo");
            try (var files = java.nio.file.Files.list(root)) {
                check(files.noneMatch(path -> path.toString().endsWith(".tmp")), "Failed save cleans temporary file");
            }
            SessionStore.write(root, next);
            check(SessionStore.read(root).equals(next), "Atomic replacement restores both new graph and tempo");
            check(java.nio.file.Files.readString(root.resolve("groove-tempo.txt")).equals("123"), "Legacy files remain untouched");
            java.nio.file.Files.writeString(root.resolve(SessionStore.FILE), "{}");
            try {
                SessionStore.read(root);
                throw new AssertionError("Corrupt new save must not silently fall back to stale legacy files");
            } catch (java.io.IOException expected) { }
            invalid(() -> new SessionStore.Saved(Graph.demo(), Double.NaN));
        } finally {
            try (var files = java.nio.file.Files.list(root)) {
                for (var file : files.toList()) java.nio.file.Files.delete(file);
            }
            java.nio.file.Files.delete(root);
        }
    }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid JSON rejection");
    }
}

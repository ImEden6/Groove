package com.mervyn.groove.music;

import com.mervyn.groove.client.ui.EditorState;
import groove.engine.Graph;
import groove.engine.GraphCompiler;
import groove.engine.NodeParam;
import groove.engine.NodeType;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Saved patches from before the loop-gain limit, commits, encoding and the editor controls. */
final class FeedbackRuleTests {
    static void run() throws Exception {
        savedSession();
        editorProject();
        commits();
        encoding();
        editor();
        System.out.println("Feedback rule checks passed.");
    }

    /** tone into bus(gain), bus into delay and back, bus to output. */
    private static Graph loop(double gain, boolean freeRun) {
        Map<String, Double> delay = freeRun ? Map.of(NodeParam.FRAMES, 1000.0, NodeParam.FREE_RUN, 1.0) : Map.of(NodeParam.FRAMES, 1000.0);
        return new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, gain)),
                new Graph.Node("delay", NodeType.DELAY, delay),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("bus", "delay"),
                        Graph.edge("delay", "bus"), new Graph.Edge("bus", "out", "out", "audio")));
    }

    private static boolean freeRunning(Graph graph, String id) {
        return graph.nodes().stream().anyMatch(n -> n.id().equals(id) && n.params().getOrDefault(NodeParam.FREE_RUN, 0.0) == 1.0);
    }

    private static void savedSession() throws Exception {
        var root = Files.createTempDirectory("groove-feedback-");
        try {
            // Written by hand, the way an older Groove saved it: SessionStore.write would refuse it now.
            String old = GraphJson.encode(loop(1.0, false));
            Files.writeString(root.resolve(SessionStore.FILE), new com.google.gson.Gson().toJson(
                    Map.of("version", 1, "bpm", 120.0, "graphJson", old)));
            var saved = SessionStore.read(root);
            check(saved.freeRunDelays().equals(List.of("delay")), "Old session save marks its loop delay: " + saved.freeRunDelays());
            check(freeRunning(saved.graph(), "delay"), "Migrated session graph is free-running");
            GraphCompiler.compile(saved.graph());
            SessionStore.write(root, saved);
            check(!Files.exists(root.resolve(SessionStore.FILE + ".bak")), "A migrated save is not treated as corrupt");
            check(SessionStore.read(root).freeRunDelays().isEmpty(), "Once saved, a migrated patch needs no migration");

            Files.delete(root.resolve(SessionStore.FILE));
            Files.writeString(root.resolve("groove-patch.json"), old);
            check(SessionStore.read(root).freeRunDelays().equals(List.of("delay")), "Legacy patch files migrate too");
        } finally {
            try (var files = Files.list(root)) { for (var file : files.toList()) Files.deleteIfExists(file); }
            Files.deleteIfExists(root);
        }
    }

    private static void editorProject() {
        var tag = new net.minecraft.nbt.CompoundTag();
        String old = GraphJson.encode(loop(1.0, false));
        tag.putString("Draft", old);
        tag.putString("Published", old);
        var project = new EditorProject();
        project.load(tag);
        check(!project.isUnreadable(), "An old strong loop is not 'needs a newer Groove version'");
        check(project.freeRunDelays().equals(java.util.Set.of("delay")), "Editor project reports migrated delays");
        var published = project.session().committed(System.nanoTime()).current().graph();
        check(freeRunning(published, "delay") && freeRunning(project.session().draft(), "delay"), "Draft and published both migrate");
    }

    private static void commits() {
        long now = System.nanoTime();
        var session = new EditorSession(loop(0.5, false), 120, loop(0.5, false), 120, now);
        session.edit(loop(1.0, false), 120, false, now);
        try {
            session.commit(session.revision(), now);
            throw new AssertionError("Committing a new over-limit loop must fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().startsWith("Feedback loop can exceed unity gain"), "Commit names the loop-gain rule: " + expected.getMessage());
        }
        session.edit(loop(1.0, true), 120, false, now);
        session.commit(session.revision(), now);
        invalid(() -> GraphJson.decode(GraphJson.encode(loop(1.0, false))), "Submitted over-limit patches are rejected");
        GraphJson.decode(GraphJson.encode(loop(1.0, true)));
    }

    private static void encoding() throws Exception {
        String limited = GraphJson.encode(loop(0.5, false));
        check(!limited.contains(NodeParam.FREE_RUN), "Graphs that never used free-run don't store the key");
        var editor = new EditorState(loop(0.5, false));
        editor.setKnobValue("delay", NodeParam.FREE_RUN, 1.0);
        check(freeRunning(editor.toGraph(), "delay"), "Free run control sets the key");
        editor.setKnobValue("delay", NodeParam.FREE_RUN, 0.0);
        check(editor.toGraph().equals(loop(0.5, false)), "Turning free-run off restores the exact graph");
        var fixture = FeedbackRuleTests.class.getResourceAsStream("/fixtures/phase3/signal_delay.json");
        if (fixture != null) {
            String json = new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Graph graph = GraphJson.decode(json);
            check(GraphJson.decode(GraphJson.encode(graph)).equals(graph) && !GraphJson.encode(graph).contains(NodeParam.FREE_RUN),
                    "Delay fixture round-trips without free-run");
        }
    }

    private static void editor() {
        // Closing a gain-1 loop is refused, with the reason for the status line.
        var editor = new EditorState(new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)),
                new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 1000.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("bus", "delay"),
                        new Graph.Edge("bus", "out", "out", "audio"))));
        check(!editor.canConnect("delay", "out", "bus", "in"), "Wire that closes a gain-1 loop is dimmed");
        editor.startWireDrag("delay", "out", new com.mervyn.groove.client.ui.Vec2(0, 0));
        check(!editor.completeWireDrag("bus", "in"), "Wire that closes a gain-1 loop is refused");
        String reason = editor.takeFeedbackRefusal();
        check(reason != null && reason.contains("bound 1.00"), "Refusal explains the bound: " + reason);
        check(editor.takeFeedbackRefusal() == null, "A refusal is reported once");

        // A wire refused for another rule (here a loop with no delay) is not blamed on feedback.
        var looped = new EditorState(new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("a", NodeType.MIX_BUS, Map.of()), new Graph.Node("b", NodeType.MIX_BUS, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "a"), Graph.edge("a", "b"),
                        new Graph.Edge("b", "out", "out", "audio"))));
        looped.startWireDrag("b", "out", new com.mervyn.groove.client.ui.Vec2(0, 0));
        check(!looped.completeWireDrag("a", "in"), "A loop with no delay is refused");
        check(looped.takeFeedbackRefusal() == null, "A delay-less loop is not reported as a feedback problem");

        // Free run opens the loop up.
        editor.setKnobValue("delay", NodeParam.FREE_RUN, 1.0);
        editor.connect("delay", "out", "bus", "in");
        check(editor.edges().contains(Graph.edge("delay", "bus")) && editor.hasFreeRunningLoop(), "A free-running delay may close the loop");
        editor.setKnobValue("delay", NodeParam.FREE_RUN, 0.0);
        check(freeRunning(editor.toGraph(), "delay") && editor.takeFeedbackRefusal() != null,
                "Turning free-run off is refused while the loop is over the limit");

        // Knob drags stop at the limit and explain why.
        var limited = new EditorState(loop(0.5, false));
        limited.setKnobValue("bus", NodeParam.GAIN, 0.95);
        check(limited.node("bus").params().get(NodeParam.GAIN) == 0.95, "Gain may reach the limit");
        limited.setKnobValue("bus", NodeParam.GAIN, 0.96);
        check(limited.node("bus").params().get(NodeParam.GAIN) == 0.95 && limited.takeFeedbackRefusal() != null, "Gain stops at the limit");

        // A graph already over the limit stays editable, but can't get worse.
        var over = new EditorState(loop(1.0, false));
        over.setKnobValue("tone", NodeParam.FREQUENCY, 330.0);
        check(over.node("tone").params().get(NodeParam.FREQUENCY) == 330.0, "Unrelated knobs still work on an over-limit graph");
        over.setKnobValue("bus", NodeParam.GAIN, 0.8);
        check(over.node("bus").params().get(NodeParam.GAIN) == 0.8, "Lowering the gain is allowed");
        over.setKnobValue("bus", NodeParam.GAIN, 1.0);
        check(over.node("bus").params().get(NodeParam.GAIN) == 0.8, "Once under the limit, the gain can't go back over");
    }

    private static void invalid(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

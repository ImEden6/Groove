package com.mervyn.groove.music;

import com.mervyn.groove.client.ui.EditorState;
import groove.engine.*;
import java.util.*;

final class PitchEntryTests {
    static void run() {
        String json = """
                {"version":3,"nodes":[
                  {"id":"tone","type":"tone","params":{"frequency":"Bb2"}},
                  {"id":"seq","type":"scale_sequence","params":{"root":"C4","value1":2,"value2":4}},
                  {"id":"chord","type":"chord","params":{"chord":1}},
                  {"id":"out","type":"output","params":{}}],
                 "edges":[
                  {"fromNode":"tone","fromPort":"out","toNode":"seq","toPort":"in"},
                  {"fromNode":"seq","fromPort":"out","toNode":"chord","toPort":"in"},
                  {"fromNode":"chord","fromPort":"out","toNode":"out","toPort":"in"}]}
                """;
        Graph graph = GraphJson.decode(json);
        check(graph.nodes().getFirst().params().get("frequency") == Pitch.hz("Bb2"), "Note frequency import");
        check(graph.nodes().get(1).params().get("root") == 60, "Scale root import");
        check(GraphJson.decode(GraphJson.encode(graph)).equals(graph), "Canonical numeric round trip");
        invalid(() -> GraphJson.decode(json.replace("Bb2", "H4")));
        invalid(() -> GraphJson.decode(json.replace("Bb2", "C4junk")));
        invalid(() -> GraphJson.decode(json.replace("C4", "C-1")));
        EditorState editor = new EditorState(graph);
        editor.setKnobText("tone","frequency","F#4");
        check(editor.node("tone").params().get("frequency") == Pitch.hz("F#4"), "Editor accepts note frequency");
        editor.undo();
        check(editor.node("tone").params().get("frequency") == Pitch.hz("Bb2"), "Note entry undoes once");
        editor.setKnobText("seq","root","D3");
        check(editor.node("seq").params().get("root") == 50, "Editor accepts scale root");
        editor.stepKnobValue("seq","value0",1,true);
        check(editor.node("seq").params().get("value0") == 1, "Fine keyboard entry changes a scale degree");
        editor.setKnobValue("chord","chord",7);
        editor.setKnobValue("chord","inversion",4);
        editor.setKnobValue("chord","chord",0);
        check(editor.node("chord").params().get("inversion") == 2, "Shorter chord clamps inversion");
        editor.undo();
        check(editor.node("chord").params().get("inversion") == 4, "Chord and inversion undo together");
        Graph draft = new Graph(3,List.of(new Graph.Node("seq",NodeType.SCALE_SEQUENCE,EditorState.defaultParams(NodeType.SCALE_SEQUENCE))),List.of());
        check(GraphJson.decodeDraft(GraphJson.encode(draft)).equals(draft), "All eight sequence controls survive draft storage");
        check(EditorState.clampParam(NodeType.FILTER,"mode",3,Map.of()) == 3, "Filter editor permits notch");
        check(EditorState.clampParam(NodeType.ENVELOPE,"mode",3,Map.of()) == 1, "Envelope modes unchanged");
        System.out.println("Pitch authoring, editor and persistence checks passed.");
    }
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
}

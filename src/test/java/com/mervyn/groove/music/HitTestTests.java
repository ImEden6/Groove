package com.mervyn.groove.music;

import com.mervyn.groove.client.ui.EditorState;
import com.mervyn.groove.client.ui.InputController;
import com.mervyn.groove.client.ui.Vec2;
import groove.engine.Graph;
import groove.engine.NodeType;
import java.util.List;
import java.util.Map;

final class HitTestTests {
    static void run() {
        // tone is drawn first, so euclid's card covers tone's output port at (148, 31).
        var graph = new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("euclid", NodeType.EUCLID, Map.of())), List.of());
        EditorState state = new EditorState(graph);
        state.layout().place("tone", new Vec2(0, 0));
        state.layout().place("euclid", new Vec2(100, 0));
        check(body("euclid").equals(state.hitAt(new Vec2(148, 31)).orElseThrow()), "Covered port loses to the card on top");
        check(body("euclid").equals(state.hitAt(new Vec2(120, 10)).orElseThrow()), "Overlap hits the top card");
        check(body("tone").equals(state.hitAt(new Vec2(50, 20)).orElseThrow()), "Uncovered part hits the bottom card");
        check(new EditorState.Hit("euclid", "out", true).equals(state.hitAt(new Vec2(248, 31)).orElseThrow()), "Visible port beats its own card");
        check(new EditorState.Hit("euclid", "in", false).equals(state.hitAt(new Vec2(100, 31)).orElseThrow()), "Top node's port beats the card below");
        check(state.hitAt(new Vec2(500, 500)).isEmpty(), "Empty canvas hits nothing");

        var input = new InputController(state);
        input.mouseDown(InputController.Button.PRIMARY, state.toScreen(new Vec2(120, 10)).x(), state.toScreen(new Vec2(120, 10)).y(), false);
        input.mouseUp();
        check(state.selection().equals(java.util.Set.of("euclid")), "Clicking an overlap selects the top card");
        input.mouseDown(InputController.Button.PRIMARY, state.toScreen(new Vec2(104, 31)).x(), state.toScreen(new Vec2(104, 31)).y(), false);
        input.mouseUp();
        check(state.selection().equals(java.util.Set.of("euclid")) && !state.isWireDragging(), "Clicking an input port inside its card grabs the card");
        // (92, 31) is inside euclid's input-port magnet but visibly on tone's card.
        input.mouseDown(InputController.Button.PRIMARY, state.toScreen(new Vec2(92, 31)).x(), state.toScreen(new Vec2(92, 31)).y(), false);
        input.mouseUp();
        check(state.selection().equals(java.util.Set.of("tone")), "An input port's magnet doesn't hide the card beneath from clicks");
        check("tone".equals(state.cardAt(new Vec2(92, 31)).orElse(null)), "Sample drops ignore input-port magnets");
        check(state.cardAt(new Vec2(256, 31)).isEmpty(), "Sample drops ignore output-port magnets outside any card");
        check("euclid".equals(state.cardAt(new Vec2(148, 31)).orElse(null)), "Sample drops target the top card");
        input.mouseDown(InputController.Button.PRIMARY, state.toScreen(new Vec2(148, 31)).x(), state.toScreen(new Vec2(148, 31)).y(), false);
        check(!state.isWireDragging(), "A covered output port can't start a wire");
        input.mouseUp();
        var lone = new EditorState(new Graph(3, List.of(new Graph.Node("euclid", NodeType.EUCLID, Map.of())), List.of()));
        lone.layout().place("euclid", new Vec2(100, 0));
        var panInput = new InputController(lone);
        panInput.spaceKeyChanged(true);
        Vec2 before = lone.toWorld(0, 0);
        panInput.mouseDown(InputController.Button.PRIMARY, lone.toScreen(new Vec2(92, 31)).x(), lone.toScreen(new Vec2(92, 31)).y(), false);
        panInput.mouseDrag(0, 0, 10, 0, (x, y) -> true);
        panInput.mouseUp();
        check(!lone.toWorld(0, 0).equals(before), "Space-drag from an input port's magnet on empty canvas pans");
        System.out.println("Editor hit-test checks passed.");
    }
    private static EditorState.Hit body(String id) { return new EditorState.Hit(id, null, false); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

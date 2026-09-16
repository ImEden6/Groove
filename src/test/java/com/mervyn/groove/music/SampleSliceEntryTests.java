package com.mervyn.groove.music;

import com.mervyn.groove.client.ui.EditorState;
import groove.engine.*;
import groove.engine.samples.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;

final class SampleSliceEntryTests {
    static void run() {
        AssetRef ref=FactorySamples.ref("factory:basic/kick.wav");
        Graph graph=new Graph(3,List.of(new Graph.Node("sample",NodeType.GENERATOR_SAMPLE,
                Map.of("startFrame",64.0,"endFrame",1024.0,"reverse",1.0),ref),
                new Graph.Node("slice",NodeType.SAMPLE_SLICE,Map.of("slices",4.0,"index",2.0,"reverse",1.0)),
                new Graph.Node("out",NodeType.OUTPUT,Map.of())),List.of(Graph.edge("sample","slice"),Graph.edge("slice","out")));
        check(GraphJson.decode(GraphJson.encode(graph)).equals(graph),"Slice graph JSON round trip");
        var snapshot=new MusicPackets.Snapshot(UUID.randomUUID(),new SessionTimeline(graph,120,0).snapshot(0));
        RegistryFriendlyByteBuf wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            MusicPackets.Snapshot.CODEC.encode(wire,snapshot);
            check(MusicPackets.Snapshot.CODEC.decode(wire).equals(snapshot),"Slice graph snapshot round trip");
        } finally { wire.release(); }
        EditorState editor=new EditorState(graph);
        editor.setKnobValue("slice","index",99);
        check(editor.node("slice").params().get("index")==3,"Slice index clamps to slice count");
        editor.setKnobValue("slice","slices",2);
        check(editor.node("slice").params().get("index")==1,"Changing slice count clamps index");
        editor.undo();
        check(editor.node("slice").params().get("slices")==4 && editor.node("slice").params().get("index")==3,"Slice controls undo together");
        editor.setKnobValue("sample","startFrame",2000);
        check(editor.node("sample").params().get("endFrame")==2001,"Start keeps explicit region nonempty");
        editor.setKnobValue("sample","endFrame",1000);
        check(editor.node("sample").params().get("startFrame")==999,"End keeps explicit region nonempty");
        editor.setKnobValue("sample","endFrame",0);
        check(editor.node("sample").params().get("endFrame")==0,"End zero selects asset end");
        editor.stepKnobValue("slice","reverse",-1,true);
        check(editor.node("slice").params().get("reverse")==0,"Direction can be stepped with keyboard");
        Graph draft=new Graph(3,List.of(new Graph.Node("slice",NodeType.SAMPLE_SLICE,EditorState.defaultParams(NodeType.SAMPLE_SLICE))),List.of());
        check(GraphJson.decodeDraft(GraphJson.encode(draft)).equals(draft),"Slice editor draft round trip");
        var prepared=com.mervyn.groove.client.music.SampleLibrary.prepare(new SessionTimeline(graph,120,0).snapshot(0));
        check(prepared.timeline().current().preparedSampleBytes()>0,"App prepares sample regions for playback");
        System.out.println("Sample slicing editor, persistence, packet and preparation checks passed.");
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}

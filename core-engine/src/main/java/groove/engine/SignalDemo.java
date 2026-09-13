package groove.engine;

import java.util.List;
import java.util.Map;

/** Slow shared-clock filter sweep with quiet, 250 ms feedback echoes. */
public final class SignalDemo {
    private SignalDemo() {}
    public static Graph graph() {
        return new Graph(3, List.of(
                new Graph.Node("tone",NodeType.TONE,Map.of("frequency",110.0,"gain",.15,"wave",1.0)),
                new Graph.Node("rhythm",NodeType.EUCLID,Map.of("steps",8.0,"pulses",3.0)),
                new Graph.Node("render",NodeType.AUDIO_RENDER,Map.of()),
                new Graph.Node("lfo",NodeType.LFO,Map.of("rate",.2)),
                new Graph.Node("range",NodeType.ATTENUVERTER,Map.of("scale",1500.0,"offset",1800.0)),
                new Graph.Node("filter",NodeType.FILTER,Map.of("resonanceQ",1.5)),
                new Graph.Node("mix",NodeType.MIX_BUS,Map.of("gain",.65)),
                new Graph.Node("delay",NodeType.DELAY,Map.of("frames",12000.0)),
                new Graph.Node("out",NodeType.OUTPUT,Map.of())), List.of(
                Graph.edge("tone","rhythm"),Graph.edge("rhythm","render"),Graph.edge("render","filter"),
                Graph.edge("lfo","range"),new Graph.Edge("range","out","filter","cutoff"),
                Graph.edge("filter","mix"),Graph.edge("delay","mix"),Graph.edge("mix","delay"),
                new Graph.Edge("mix","out","out","audio")));
    }
}

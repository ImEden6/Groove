package groove.engine.samples;

import groove.engine.Graph;
import groove.engine.NodeType;
import groove.engine.NodeParam;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/** Small original procedural kit, encoded deterministically; no third-party sample licensing. */
public final class FactorySamples {
    private static final int RATE = 24000;
    public static final String BREAK = "factory:basic/break.wav";
    /** Seconds per bar of the break: one cycle at 96 BPM, so eight slices fall on eighth notes. */
    public static final double BREAK_SECONDS = 2.5;
    private static final Map<String, byte[]> FILES = Map.of("factory:basic/kick.wav", encode(hit(0)),
            "factory:basic/snare.wav", encode(hit(1)), "factory:basic/hat.wav", encode(hit(2)), BREAK, encode(drumBreak()));
    public static List<String> ids() { return FILES.keySet().stream().sorted().toList(); }
    public static byte[] bytes(String id) { byte[] value = FILES.get(id); return value == null ? null : value.clone(); }
    public static AssetRef ref(String id) { return new AssetRef(id, AssetRef.hash(FILES.get(id))); }
    /** One kick (0), snare (1) or hat (2), before 16-bit scaling. */
    private static double[] hit(int instrument) {
        int rate = RATE, frames = instrument == 0 ? 14400 : instrument == 1 ? 7200 : 2400;
        double[] out = new double[frames];
        long noise = 42; double phase = 0, previous = 0;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / rate;
            noise = (noise * 1664525 + 1013904223) & 0xffffffffL;
            double n = noise / 2147483648.0 - 1;
            phase += (48 + 110 * StrictMath.exp(-t * 32)) / rate;
            double value = instrument == 0 ? StrictMath.sin(phase * 2 * StrictMath.PI) * StrictMath.exp(-t * 9)
                    : instrument == 1 ? (n * .7 + StrictMath.sin(t * 180 * 2 * StrictMath.PI) * .3) * StrictMath.exp(-t * 19)
                    : (n - previous) * .4 * StrictMath.exp(-t * 45);
            previous = n;
            value *= Math.min(1, i / 24.0) * Math.min(1, (frames - i) / 120.0);
            out[i] = value;
        }
        return out;
    }

    /** One bar of eighths: kick, snare on two and four, hats throughout, a ghost snare and a closing roll. */
    private static double[] drumBreak() {
        double[] kick = hit(0), snare = hit(1), hat = hit(2);
        int slot = (int) Math.round(BREAK_SECONDS * RATE / 8);
        double[] out = new double[slot * 8];
        String[] slots = {"kh", "h", "sh", "gh", "kh", "kh", "sh", "hgs"};
        for (int i = 0; i < 8; i++)
            for (char c : slots[i].toCharArray()) {
                double[] source = c == 'k' ? kick : c == 'h' ? hat : snare;
                double gain = c == 'k' ? .9 : c == 's' ? .8 : c == 'g' ? .3 : .35;
                // The closing roll's second snare lands half a slot later
                int at = i * slot + (c == 's' && i == 7 ? slot / 2 : 0);
                for (int f = 0; f < source.length && at + f < out.length; f++) out[at + f] += source[f] * gain;
            }
        double peak = 0;
        for (double v : out) peak = Math.max(peak, Math.abs(v));
        for (int i = 0; i < out.length; i++) out[i] *= .95 / peak;
        return out;
    }

    private static byte[] encode(double[] values) {
        int rate = RATE, frames = values.length;
        ByteBuffer b = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + frames * 2).putInt(0x45564157).putInt(0x20746d66).putInt(16);
        b.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(frames * 2);
        for (double value : values) b.putShort((short) Math.round(value * 26000));
        return b.array();
    }
    /** The kit from demo() as a v3 signal graph through a short room reverb, mixed under the dry beat. */
    public static Graph reverbDemo() {
        return new Graph(3, List.of(new Graph.Node("kick", NodeType.GENERATOR_SAMPLE, Map.of(), ref("factory:basic/kick.wav")),
                new Graph.Node("beat", NodeType.EUCLID, Map.of(NodeParam.STEPS, 8.0, NodeParam.PULSES, 4.0)),
                new Graph.Node("hat", NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, .4), ref("factory:basic/hat.wav")),
                new Graph.Node("hats", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 7.0)),
                new Graph.Node("mix", NodeType.STACK, Map.of()),
                new Graph.Node("kit", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("room", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, .6, NodeParam.DAMPING_HZ, 8000.0)),
                new Graph.Node("roomLevel", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .35)),
                new Graph.Node("master", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .9)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("kick", "beat"), Graph.edge("hat", "hats"), Graph.edge("beat", "mix"), Graph.edge("hats", "mix"),
                        Graph.edge("mix", "kit"), Graph.edge("kit", "master"), Graph.edge("kit", "room"),
                        Graph.edge("room", "roomLevel"), Graph.edge("roomLevel", "master"),
                        new Graph.Edge("master", "out", "out", "audio")));
    }
    public static Graph demo() {
        return new Graph(2, List.of(new Graph.Node("kick", NodeType.GENERATOR_SAMPLE, Map.of(), ref("factory:basic/kick.wav")),
                new Graph.Node("beat", NodeType.EUCLID, Map.of(NodeParam.STEPS, 8.0, NodeParam.PULSES, 4.0)),
                new Graph.Node("hat", NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, .4), ref("factory:basic/hat.wav")),
                new Graph.Node("hats", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 7.0)),
                new Graph.Node("mix", NodeType.STACK, Map.of()), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("kick", "beat"), Graph.edge("hat", "hats"), Graph.edge("beat", "mix"), Graph.edge("hats", "mix"), Graph.edge("mix", "out")));
    }
}

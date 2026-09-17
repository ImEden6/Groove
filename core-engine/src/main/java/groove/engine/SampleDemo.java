package groove.engine;

import groove.engine.samples.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Offline sample demo using only the bundled procedural kit: four scores through echo and two reverbs. */
public final class SampleDemo {
    public static final int CYCLES = 7, BPM = 120;
    /** 14 s of music, then a 2 s tail so the reverbs ring out. */
    public static final int TOTAL_FRAMES = LiveRenderer.SAMPLE_RATE * 16;

    public static void main(String[] args) throws Exception {
        Demo.writeWav(Path.of(args.length == 0 ? "sample-demo.wav" : args[0]), render(), LiveRenderer.SAMPLE_RATE);
    }

    static float[] render() {
        AssetRef kick = FactorySamples.ref("factory:basic/kick.wav");
        AssetRef snare = FactorySamples.ref("factory:basic/snare.wav");
        AssetRef hat = FactorySamples.ref("factory:basic/hat.wav");
        Map<AssetRef, SampleData> bank = Map.of(
                kick, WavDecoder.decode(FactorySamples.bytes(kick.assetId())),
                snare, WavDecoder.decode(FactorySamples.bytes(snare.assetId())),
                hat, WavDecoder.decode(FactorySamples.bytes(hat.assetId())));
        Pattern k = Pattern.sample(new SampleVoice(kick, 1, .7, 0)).slice(2, 0, false);
        Pattern s = Pattern.sample(new SampleVoice(snare, 1, .5, .25)).slice(2, 0, false);
        Pattern r = Pattern.sample(new SampleVoice(snare, .8, .4, -.25)).slice(2, 0, true);
        Pattern h = Pattern.sample(new SampleVoice(hat, 1, .35, .35));
        Pattern drumLoop = Pattern.polymeter(8, k, h, s, h, k, h, r, h).swing(16, 0.6);
        // Cycle 3 plays the bar reversed
        Pattern drums = Pattern.alternate(drumLoop, drumLoop, drumLoop, drumLoop.reverse());
        Tone pulseBass = new Tone(Tone.Wave.PULSE, Pitch.hz("C2"), .22, 0, 1600, 2.5, 0.35);
        Pattern bass = Pattern.tone(pulseBass).euclid(8, 3, 0).swing(16, 0.6);
        Tone pulseLead = new Tone(Tone.Wave.PULSE, Pitch.hz("C4"), .18, .2, 2400, 1.8, 0.2);
        Pattern lead = Pattern.tone(pulseLead).scaleSequence(Pitch.midi("C4"), Pitch.Scale.MINOR_PENTATONIC, 8,
                new int[]{0, 2, 3, 4, 3, 2, 0, -1}).swing(16, 0.6);
        // Sustained pad: the looped body of the kick's first half, one whole-cycle note voiced 2:3:4
        double[][] voicing = {{2, -.5}, {3, .5}, {4, 0}};
        Pattern[] padVoices = new Pattern[voicing.length];
        for (int i = 0; i < voicing.length; i++)
            padVoices[i] = Pattern.sample(new SampleVoice(kick, voicing[i][0], .45, voicing[i][1], 20000, Biquad.DEFAULT_Q,
                    SampleRegion.ALL, true, .25, .6, 40).slice(2, 0, false));
        Pattern pad = Pattern.stack(padVoices);

        Transport transport = new Transport(LiveRenderer.SAMPLE_RATE, BPM, 4);
        Graph graph = graph();
        return Demo.render(Map.of(
                        "drums", Score.compile(drums, transport, CYCLES, bank),
                        "bass", Score.compile(bass, transport, CYCLES),
                        "lead", Score.compile(lead, transport, CYCLES),
                        "pad", Score.compile(pad, transport, CYCLES, bank)),
                graph, new SessionState(1, 0, 0, BPM, true, graph), TOTAL_FRAMES);
    }

    /**
     * Drums get a short room mixed low under the dry signal, bass stays dry, the lead goes through a synced
     * 1/8 echo and the long hall, and the pad feeds the hall. The hall sits after the echo loop, not inside it.
     */
    public static Graph graph() {
        return new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("drums", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("bass", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("lead", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("pad", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("room", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, .6, NodeParam.DAMPING_HZ, 8000.0)),
                new Graph.Node("roomLevel", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .35)),
                new Graph.Node("echoMix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .55)),
                new Graph.Node("echo", NodeType.DELAY, Map.of(NodeParam.SYNC, 1.0, NodeParam.DIVISION, 2.0)),
                new Graph.Node("hallSend", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)),
                new Graph.Node("hall", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 2.5, NodeParam.DAMPING_HZ, 5000.0, NodeParam.PRE_DELAY_MS, 20.0)),
                new Graph.Node("padDry", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .3)),
                new Graph.Node("master", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .7)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
            List.of(
                // Placeholder patterns: the offline scores replace these sources' audio
                Graph.edge("tone", "drums"), Graph.edge("tone", "bass"), Graph.edge("tone", "lead"), Graph.edge("tone", "pad"),
                Graph.edge("drums", "master"), Graph.edge("drums", "room"), Graph.edge("room", "roomLevel"), Graph.edge("roomLevel", "master"),
                Graph.edge("bass", "master"),
                Graph.edge("lead", "echoMix"), Graph.edge("echo", "echoMix"), Graph.edge("echoMix", "echo"), Graph.edge("echoMix", "master"),
                Graph.edge("echoMix", "hallSend"), Graph.edge("pad", "hallSend"), Graph.edge("hallSend", "hall"), Graph.edge("hall", "master"),
                Graph.edge("pad", "padDry"), Graph.edge("padDry", "master"),
                new Graph.Edge("master", "out", "out", "audio")));
    }
}

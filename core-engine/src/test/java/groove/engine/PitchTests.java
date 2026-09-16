package groove.engine;

import java.util.*;

/** Pitch range validation must include future branches, and queries must remain partition-independent. */
final class PitchTests {
    static void run() {
        check(Pitch.hz("A4") == 440 && Pitch.midi("C4") == 60, "Scientific pitch reference");
        check(Pitch.hz("F#4") == Pitch.hz("Gb4") && Pitch.midi("B#3") == 60, "Enharmonic notes");
        check(Pitch.midi("C-1") == 0 && Pitch.midi("G9") == 127, "MIDI endpoints");
        for (String bad : new String[]{"C", "H4", "C##4", "C10", "Cb-1", "G#9", "NaN", "C4junk"}) invalid(() -> Pitch.hz(bad));
        check(Pitch.Scale.MAJOR.semitones(-1) == -1 && Pitch.Scale.MAJOR.semitones(-7) == -12, "Negative degrees wrap downwards");
        for (Pitch.Scale scale : Pitch.Scale.values()) check(Pitch.degreeHz(60, scale, 0) == Pitch.hz("C4"), "Scale root");
        Tone tone = new Tone(Tone.Wave.SAW, 220, .6, -.3, 2300, 2, 0.5);
        Pattern source = Pattern.tone(tone);
        Tone octave = source.transpose(12).query(new Arc(0, 1)).getFirst().tone();
        check(octave.frequency() == 440 && octave.gain() == .6 && octave.pan() == -.3 && octave.resonanceQ() == 2 && octave.pulseWidth() == 0.5, "Transpose preserves voice settings");
        Tone pulseTone = new Tone(Tone.Wave.PULSE, 220, .6, 0, 2000, 1, 0.25);
        Tone transposedPulse = Pitch.withFrequency(pulseTone, 440);
        check(transposedPulse.pulseWidth() == 0.25 && transposedPulse.wave() == Tone.Wave.PULSE, "withFrequency preserves pulseWidth");
        List<Event> chord = source.chord(Pitch.Chord.MINOR, 1).query(new Arc(0, 1));
        check(chord.size() == 3 && Math.abs(chord.getFirst().tone().frequency() - 220*Math.pow(2,3/12.0)) < 1e-9 && chord.getFirst().tone().pulseWidth() == 0.5, "Minor first inversion");
        check(chord.getLast().tone().frequency() == 440 && Math.abs(chord.stream().mapToDouble(e -> e.tone().gain()).sum() - .6) < 1e-12, "Chord gain budget");
        int[] degrees = {0, 2, -1};
        Pattern sequence = source.scaleSequence(60, Pitch.Scale.MAJOR, 2, degrees);
        degrees[0] = 40;
        List<Event> notes = sequence.query(new Arc(0, 2));
        check(notes.stream().map(e -> e.tone().frequency()).toList().equals(List.of(Pitch.hz("C4"),Pitch.hz("E4"),Pitch.hz("B3"),Pitch.hz("C4"))), "Sequence wraps across cycles and owns degrees");
        for (Pattern p : List.of(source.transpose(-12), source.chord(Pitch.Chord.DOMINANT_9, 2), sequence,
                sequence.chord(Pitch.Chord.MINOR_7, 1).transpose(7).probability(.7, 32))) partition(p);

        Graph g = graph(NodeType.SCALE_SEQUENCE, Map.of("steps",3.0,"stepsPerCycle",2.0,"value1",2.0,"value2",-1.0));
        check(GraphCompiler.compile(g).pattern().query(new Arc(0,2)).stream().map(e -> e.tone().frequency()).toList()
                .equals(notes.stream().map(e -> e.tone().frequency()).toList()), "Graph scale sequence");
        invalid(() -> GraphCompiler.compile(graph(NodeType.SCALE_SEQUENCE, Map.of("value3",64.0))));
        invalid(() -> GraphCompiler.compile(graph(NodeType.SCALE_SEQUENCE, Map.of("value7",.5))));
        invalid(() -> GraphCompiler.compile(graph(NodeType.TRANSPOSE, Map.of("semitones",49.0))));
        invalid(() -> GraphCompiler.compile(graph(NodeType.CHORD, Map.of("inversion",3.0))));
        for (NodeType type : List.of(NodeType.TRANSPOSE,NodeType.CHORD,NodeType.SCALE_SEQUENCE)) {
            Graph plain = graph(type,Map.of());
            var nodes = new ArrayList<>(plain.nodes());
            nodes.set(0,new Graph.Node("tone",NodeType.GENERATOR_SAMPLE,Map.of(),groove.engine.samples.FactorySamples.ref("factory:basic/kick.wav")));
            Graph sampleGraph = new Graph(3,nodes,plain.edges());
            invalid(() -> GraphCompiler.compile(sampleGraph));
            nodes = new ArrayList<>(plain.nodes());
            nodes.set(2,new Graph.Node("out",NodeType.AUDIO_RENDER,Map.of()));
            nodes.add(new Graph.Node("sink",NodeType.OUTPUT,Map.of()));
            var edges = new ArrayList<>(plain.edges()); edges.add(new Graph.Edge("out","out","sink","audio"));
            check(GraphCompiler.compile(new Graph(3,nodes,edges)).signals().sourceCount() == 1, "Pitch node routes through signal graph");
        }
        // Cycle zero contains only a safe pitch. A later branch would overflow after transpose.
        Graph future = new Graph(3,List.of(n("a",NodeType.TONE,Map.of()),n("b",NodeType.TONE,Map.of("frequency",10000.0)),
                n("alt",NodeType.ALTERNATE,Map.of()),n("shift",NodeType.TRANSPOSE,Map.of("semitones",12.0)),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("a","alt"),Graph.edge("b","alt"),Graph.edge("alt","shift"),Graph.edge("shift","out")));
        invalid(() -> GraphCompiler.compile(future));
        Graph crowded = new Graph(3,List.of(n("tone",NodeType.TONE,Map.of()),n("fast",NodeType.FAST,Map.of("factor",16.0)),
                n("chord",NodeType.CHORD,Map.of("chord",7.0)),n("again",NodeType.CHORD,Map.of()),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("tone","fast"),Graph.edge("fast","chord"),Graph.edge("chord","again"),Graph.edge("again","out")));
        invalid(() -> GraphCompiler.compile(crowded));
        Pattern music = sequence.chord(Pitch.Chord.MINOR,0);
        Score score = Score.compile(music,new Transport(48000,120,4),2);
        float[] full = new float[96000], chunks = new float[full.length];
        new Renderer(score,32).render(full,0,full.length/2);
        Renderer renderer = new Renderer(score,32);
        for (int at=0;at<chunks.length/2;at+=127) renderer.render(chunks,at,Math.min(127,chunks.length/2-at));
        check(Arrays.equals(full,chunks), "Musical rendering is block-size independent");
        liveAllocation();
        System.out.println("Pitch, harmony, range and partition regressions passed.");
    }
    private static void liveAllocation() {
        Graph graph = new Graph(3,List.of(n("tone",NodeType.TONE,Map.of()),
                n("seq",NodeType.SCALE_SEQUENCE,Map.of("value1",2.0,"value2",4.0,"value3",6.0)),
                n("chord",NodeType.CHORD,Map.of()),n("shift",NodeType.TRANSPOSE,Map.of("semitones",-12.0)),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("tone","seq"),Graph.edge("seq","chord"),Graph.edge("chord","shift"),Graph.edge("shift","out")));
        var program = new LiveRenderer.Program(new SessionState(1,0,0,120,true,graph),GraphCompiler.compile(graph));
        var renderer = new LiveRenderer(); renderer.publish(new LiveRenderer.Timeline(program,null));
        float[] block = new float[128];
        double energy = 0;
        for(int i=0;i<2000;i++) {
            renderer.render(block,64,Math.round(i*64*1e9/48000));
            for(float value : block) energy += value*value;
        }
        check(energy>1 && renderer.scheduleMisses()==0,"Pitch chain renders audible scheduled voices");
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean counter) || !counter.isThreadAllocatedMemorySupported()) return;
        counter.setThreadAllocatedMemoryEnabled(true);
        long id=Thread.currentThread().threadId(),before=counter.getThreadAllocatedBytes(id);
        for(int i=2000;i<4000;i++) renderer.render(block,64,Math.round(i*64*1e9/48000));
        long bytes=counter.getThreadAllocatedBytes(id)-before;
        check(bytes==0 && renderer.scheduleMisses()==0,"Pitch-chain callback allocation/starvation: "+bytes);
    }
    private static void partition(Pattern pattern) {
        List<Event> full = pattern.query(new Arc(-2,4));
        for (int i=0;i<96;i++) {
            Arc part = new Arc(-2+i/16.0,-2+(i+1)/16.0);
            List<Event> expected = new ArrayList<>();
            for (Event e : full) {
                Arc clipped = e.part().intersect(part);
                if (clipped != null) expected.add(new Event(e.whole(),clipped,e.tone(),e.sample()));
            }
            check(pattern.query(part).equals(expected), "Pitch query partition equivalence");
        }
        check(pattern.query(new Arc(1,1)).isEmpty(), "Empty pitch query");
    }
    private static Graph graph(NodeType type, Map<String,Double> params) {
        return new Graph(3,List.of(n("tone",NodeType.TONE,Map.of()),n("pitch",type,params),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("tone","pitch"),Graph.edge("pitch","out")));
    }
    private static Graph.Node n(String id, NodeType type, Map<String,Double> params) { return new Graph.Node(id,type,params); }
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
}

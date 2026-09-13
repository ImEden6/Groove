package groove.engine;

import java.util.*;

final class SignalTests {
    static void run() {
        validation(); modulation(); feedback(); filter(); live(); multipleSources(); multipleSourceLifecycle(); triggerRenderPlumbing(); arbitraryTriggerEnvelope(); allocation();
        System.out.println("Signal graph modulation, feedback, live and allocation checks passed.");
    }
    private static Graph.Node n(String id,NodeType type,Map<String,Double> params) { return new Graph.Node(id,type,params); }
    private static Graph base(List<Graph.Node> nodes,List<Graph.Edge> edges) {
        var all = new ArrayList<>(List.of(n("tone",NodeType.TONE,Map.of()),n("render",NodeType.AUDIO_RENDER,Map.of()),n("out",NodeType.OUTPUT,Map.of())));
        all.addAll(nodes);
        var links = new ArrayList<>(List.of(Graph.edge("tone","render"))); links.addAll(edges);
        return new Graph(3,all,links);
    }
    private static Graph.Edge out(String from) { return new Graph.Edge(from,"out","out","audio"); }
    private static Graph feedbackGraph(int frames) {
        return base(List.of(n("mix",NodeType.MIX_BUS,Map.of("gain",.5)),n("delay",NodeType.DELAY,Map.of("frames",(double)frames))),
                List.of(Graph.edge("render","mix"),Graph.edge("mix","delay"),Graph.edge("delay","mix"),out("mix")));
    }
    private static Graph replace(Graph g,String id,Map<String,Double> params) {
        return new Graph(g.version(),g.nodes().stream().map(n -> n.id().equals(id) ? new Graph.Node(n.id(),n.type(),params,n.sample(),n.birthNanos()) : n).toList(),g.edges());
    }
    private static SessionState state(Graph g,long at,double cycle,double bpm) { return new SessionState(1,at,cycle,bpm,true,g); }
    private static SignalRuntime runtime(Graph g,SessionState s) { return GraphCompiler.compile(g).signals().runtime(s); }
    private static void validation() {
        Graph valid = feedbackGraph(64); GraphCompiler.compile(valid);
        invalid(() -> GraphCompiler.compile(feedbackGraph(63)));
        invalid(() -> GraphCompiler.compile(feedbackGraph(64_000)));
        invalid(() -> GraphCompiler.compile(new Graph(2,valid.nodes(),valid.edges())));
        invalid(() -> GraphCompiler.compile(replace(valid,"mix",Map.of("gain",Double.NaN))));
        var edges = new ArrayList<>(valid.edges()); edges.add(Graph.edge("mix","mix"));
        invalid(() -> GraphCompiler.compile(new Graph(3,valid.nodes(),edges)));
        // A delay on one branch cannot bless the parallel mix -> bypass -> mix loop.
        var nodes = new ArrayList<>(valid.nodes()); nodes.add(n("bypass",NodeType.MIX_BUS,Map.of()));
        var diamond = new ArrayList<>(valid.edges()); diamond.add(Graph.edge("mix","bypass")); diamond.add(Graph.edge("bypass","mix"));
        invalid(() -> GraphCompiler.compile(new Graph(3,nodes,diamond)));
        var mismatch = new ArrayList<>(valid.edges()); mismatch.add(new Graph.Edge("tone","out","mix","gain"));
        invalid(() -> GraphCompiler.compile(new Graph(3,valid.nodes(),mismatch)));
        GraphCompiler.compile(SignalDemo.graph());
        var delays = new ArrayList<Graph.Node>(); var chain = new ArrayList<Graph.Edge>();
        String from="render";
        for (int i=0;i<5;i++) { String id="delay"+i; delays.add(n(id,NodeType.DELAY,Map.of("frames",48000.0))); chain.add(Graph.edge(from,id)); from=id; }
        chain.add(out(from)); invalid(() -> GraphCompiler.compile(base(delays,chain)));
    }
    private static Graph controls(boolean gated) {
        return base(List.of(n("lfo",NodeType.LFO,Map.of("rate",1.0)),n("range",NodeType.ATTENUVERTER,Map.of("scale",.5,"offset",.5)),
                n("seq",NodeType.STEP_SEQUENCE,Map.of("steps",1.0,"gate",.5,"value0",.8)),
                n("env",NodeType.ENVELOPE,Map.of("attack",.1,"decay",.1,"sustain",.5,"release",.2,"mode",gated?1.0:0.0)),
                n("gain",NodeType.MIX_BUS,Map.of()),n("gain2",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("lfo","range"),new Graph.Edge("seq","trigger","env","trigger"),
                        Graph.edge("render","gain"),new Graph.Edge("range","out","gain","gain"),Graph.edge("gain","gain2"),
                        new Graph.Edge("env","out","gain2","gain"),out("gain2")));
    }
    private static void modulation() {
        Graph g = SignalGraph.assignBirths(controls(true),null,0);
        SessionState s = state(g,0,0,120);
        SignalRuntime a = runtime(g,s), b = runtime(g,s);
        close(a.control("lfo",250_000_000),1,1e-5,"Hz phase");
        close(a.control("range",250_000_000),1,1e-5,"Attenuverter");
        close(a.control("seq",250_000_000),.8,1e-12,"Step value");
        close(a.control("env",100_000_000),.5,.001,"Envelope attack in cycles");
        close(a.control("env",1_200_000_000),.25,.001,"Gated release anchored to whole end");
        SignalRuntime one = runtime(controls(false),state(controls(false),0,0,120));
        close(one.control("env",800_000_000),0,.001,"One shot releases independently of gate end");
        for (long time=0;time<3_000_000_000L;time+=31_000_000) a.control("lfo",time);
        close(a.control("lfo",2_730_000_000L),b.control("lfo",2_730_000_000L),0,"Late join phase does not depend on query history");
        Graph edited = SignalGraph.assignBirths(replace(g,"range",Map.of("scale",.2)),g,2_000_000_000L);
        close(runtime(edited,state(edited,2_000_000_000L,1,120)).control("lfo",2_730_000_000L),a.control("lfo",2_730_000_000L),0,"Unrelated edit preserves phase");
        Graph changed = SignalGraph.assignBirths(replace(g,"lfo",Map.of("rate",2.0)),g,2_000_000_000L);
        close(runtime(changed,state(changed,2_000_000_000L,1,120)).control("lfo",2_000_000_000L),0,1e-12,"Frequency edit resets phase");
        SignalRuntime tempo = runtime(g,state(g,2_000_000_000L,1,180));
        close(tempo.control("lfo",2_730_000_000L),a.control("lfo",2_730_000_000L),0,"Hz LFO independent of tempo");
        Graph sync = replace(g,"lfo",Map.of("rate",1.0,"sync",1.0));
        close(runtime(sync,state(sync,0,0,120)).control("lfo",500_000_000),1,1e-5,"Cycle synced LFO");
        var timeline = new SessionTimeline(g,120,100);
        var initial = timeline.snapshot(100).current().graph();
        Graph spoofed = new Graph(3,initial.nodes().stream().map(n -> n.type()==NodeType.LFO ? new Graph.Node(n.id(),n.type(),n.params(),null,999L) : n).toList(),initial.edges());
        var scheduled = timeline.schedule(spoofed,120,true,0,100).pending().graph();
        check(scheduled.nodes().stream().filter(n -> n.type()==NodeType.LFO).findFirst().orElseThrow().birthNanos()==100,"Server ignores client birth spoofing");
    }
    private static void feedback() {
        Graph g = feedbackGraph(64); SignalRuntime dsp = runtime(g,state(g,0,0,120)); double[] stereo = new double[2];
        for (int f=0;f<256;f++) {
            stereo[0] = f==0?1:0; stereo[1] = f==0?.4:0;
            dsp.process(stereo,Math.round(f*1e9/48000));
            double expected = f%64==0 ? Math.pow(.5,f/64+1) : 0;
            close(stereo[0],expected,1e-12,"Feedback impulse delay"); close(stereo[1],expected*.4,1e-12,"Stereo feedback independence");
        }
        dsp.reset(); stereo[0]=stereo[1]=0; dsp.process(stereo,0); close(stereo[0],0,0,"Reset clears delay history");
    }
    private static void filter() {
        Graph low=base(List.of(n("filter",NodeType.FILTER,Map.of("cutoffHz",200.0))),List.of(Graph.edge("render","filter"),out("filter")));
        Graph high=replace(low,"filter",Map.of("cutoffHz",20000.0));
        SignalRuntime a=runtime(low,state(low,0,0,120)),b=runtime(high,state(high,0,0,120));
        double[] x=new double[2],y=new double[2]; double lowEnergy=0,highEnergy=0;
        for (int i=0;i<4000;i++) {
            x[0]=y[0]=.1*Math.sin(2*Math.PI*10000*i/48000); x[1]=y[1]=0;
            long time=Math.round(i*1e9/48000); a.process(x,time); b.process(y,time);
            if(i>1000) { lowEnergy+=x[0]*x[0]; highEnergy+=y[0]*y[0]; }
            close(x[1],0,0,"Filter channels are independent");
        }
        check(lowEnergy<highEnergy*.001,"Routed low-pass attenuates high frequencies");
    }
    private static void live() {
        Graph g = SignalGraph.assignBirths(SignalDemo.graph(),null,0);
        SessionState state = state(g,0,0,120);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state,GraphCompiler.compile(g)),null);
        LiveRenderer whole = new LiveRenderer(), chunked = new LiveRenderer(); whole.publish(timeline); chunked.publish(timeline);
        float[] expected = new float[16000], actual = new float[16000]; whole.render(expected,8000,0);
        int at=0;
        while (at<8000) {
            int size = Math.min(137,8000-at); float[] buffer = new float[size*2];
            chunked.render(buffer,size,Math.round(at*1e9/48000)); System.arraycopy(buffer,0,actual,at*2,buffer.length); at+=size;
        }
        double error=0,energy=0;
        for (int i=0;i<actual.length;i++) { check(Float.isFinite(actual[i]),"Finite routed audio"); error=Math.max(error,Math.abs(actual[i]-expected[i])); energy+=actual[i]*actual[i]; }
        check(error<1e-4 && energy>1,"Chunk independent audible live routing");
        chunked.resynchronize(); whole.resynchronize();
        float[] one=new float[512],two=new float[512]; whole.render(one,256,1_000_000_000); chunked.render(two,256,1_000_000_000);
        check(Arrays.equals(one,two),"Resync resets private effect state");
    }
    private static Graph multipleGraph() {
        // Intentionally interleave sources, effects and controls: source ordinals are not node indices.
        return new Graph(3,List.of(n("out",NodeType.OUTPUT,Map.of()),n("right",NodeType.TONE,Map.of("frequency",660.0,"pan",1.0)),
                n("renderL",NodeType.AUDIO_RENDER,Map.of()),n("lfo",NodeType.LFO,Map.of()),
                n("left",NodeType.TONE,Map.of("frequency",110.0,"pan",-1.0)),n("delay",NodeType.DELAY,Map.of("frames",64.0)),
                n("renderR",NodeType.AUDIO_RENDER,Map.of()),n("filter",NodeType.FILTER,Map.of("cutoffHz",2000.0)),
                n("speed",NodeType.FAST,Map.of("factor",1.5)),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("left","speed"),Graph.edge("speed","renderL"),Graph.edge("right","renderR"),
                        Graph.edge("renderL","delay"),Graph.edge("renderR","filter"),Graph.edge("delay","mix"),Graph.edge("filter","mix"),
                        new Graph.Edge("lfo","out","mix","gain"),out("mix")));
    }
    private static void multipleSources() {
        Graph graph=multipleGraph(); LoopPlan plan=GraphCompiler.compile(graph);
        SignalGraph signals=plan.signals();
        check(signals.sourceCount()==2 && signals.sourceNodeId(0).equals("renderL") && signals.sourceNodeId(1).equals("renderR"),"Stable source mapping");
        SignalRuntime multiDsp=signals.runtime(state(graph,0,0,120));
        double[][] inputs={{1,0},{0,.25}}; double[] output=new double[2];
        invalid(() -> multiDsp.process(new double[2],0));
        invalid(() -> multiDsp.process(new double[][]{{1,0}},output,0));
        // Use a static mix for a directly measurable impulse and no control-array cross-talk.
        var plainNodes=graph.nodes().stream().filter(n -> !n.id().equals("lfo")).toList();
        var plainEdges=graph.edges().stream().filter(e -> !e.fromNode().equals("lfo")).toList();
        Graph plain=new Graph(3,plainNodes,plainEdges);
        SignalRuntime dsp=runtime(plain,state(plain,0,0,120));
        Biquad reference=new Biquad(); reference.setLowPass(2000,Biquad.DEFAULT_Q,48000);
        for(int f=0;f<128;f++) {
            inputs[0][0]=f==0?1:0; inputs[1][1]=f==0?.25:0;
            dsp.process(inputs,output,Math.round(f*1e9/48000));
            close(output[0],f==64?1:0,1e-12,"Independent source delay");
            close(output[1],reference.process(f==0?.25:0),1e-12,"Independent source filter");
        }
        // Feeding one shared pattern into two AUDIO_RENDER nodes must double its audio, not deduplicate it.
        Graph shared=base(List.of(n("render2",NodeType.AUDIO_RENDER,Map.of()),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","render2"),Graph.edge("render","mix"),Graph.edge("render2","mix"),out("mix")));
        Graph single=base(List.of(),List.of(out("render")));
        float[] both=renderFrames(shared,5000,137),one=renderFrames(single,5000,137);
        for(int i=500;i<both.length;i++) {
            double raw=Math.log((1+one[i])/(1-one[i]))/2;
            close(both[i],Math.tanh(2*raw),2e-7,"Shared patterns retain independent voices");
        }
        float[] whole=renderFrames(graph,12000,12000),chunked=renderFrames(graph,12000,137);
        for(int i=0;i<whole.length;i++) close(chunked[i],whole[i],1e-4,"Multiple-source chunk independence");
        Graph onlyLeft=without(graph,Set.of("right","renderR","filter"));
        Graph onlyRight=without(graph,Set.of("left","speed","renderL","delay"));
        float[] leftReference=renderFrames(onlyLeft,12000,12000),rightReference=renderFrames(onlyRight,12000,12000);
        for(int i=0;i<whole.length;i+=2) {
            close(whole[i],leftReference[i],1e-7,"Left pipeline matches isolated renderer");
            close(whole[i+1],rightReference[i+1],1e-7,"Right pipeline matches isolated renderer");
        }
        var crowdedNodes=new ArrayList<>(replace(graph,"left",Map.of()).nodes());
        crowdedNodes.replaceAll(n -> n.id().equals("left") ? n("left",NodeType.STACK,Map.of()) : n);
        var crowdedEdges=new ArrayList<>(graph.edges());
        for(int i=0;i<40;i++) {
            String id="crowd"+i;
            String group="group"+(i/8);
            if(i%8==0) {
                crowdedNodes.add(n(group,NodeType.STACK,Map.of()));
                crowdedEdges.add(Graph.edge(group,"left"));
            }
            crowdedNodes.add(n(id,NodeType.TONE,Map.of("frequency",110.0,"gain",.02,"pan",-1.0)));
            crowdedEdges.add(Graph.edge(id,group));
        }
        float[] crowded=renderFrames(new Graph(3,crowdedNodes,crowdedEdges),12000,12000);
        for(int i=1;i<crowded.length;i+=2) close(crowded[i],rightReference[i],1e-7,"Voice stealing stays within its source");
        var reversed=new ArrayList<>(graph.nodes()); Collections.reverse(reversed);
        float[] reordered=renderFrames(new Graph(3,reversed,graph.edges()),12000,12000);
        for(int i=0;i<whole.length;i++) close(reordered[i],whole[i],1e-7,"Node order does not swap source audio");
        var tooManyNodes=new ArrayList<Graph.Node>(); var tooManyEdges=new ArrayList<Graph.Edge>();
        for(int i=0;i<SignalGraph.MAX_AUDIO_SOURCES;i++) {
            String id="renderExtra"+i; tooManyNodes.add(n(id,NodeType.AUDIO_RENDER,Map.of()));
            tooManyEdges.add(Graph.edge("tone",id)); tooManyEdges.add(Graph.edge(id,"mix"));
        }
        tooManyNodes.add(n("mix",NodeType.MIX_BUS,Map.of())); tooManyEdges.add(Graph.edge("render","mix")); tooManyEdges.add(out("mix"));
        invalid(() -> GraphCompiler.compile(base(tooManyNodes,tooManyEdges)));
        Graph maxSources=without(base(tooManyNodes,tooManyEdges),Set.of("renderExtra0"));
        check(GraphCompiler.compile(maxSources).size()==SignalGraph.MAX_AUDIO_SOURCES,"Maximum source count retains all duplicate preview events");
        Graph costly=replace(shared,"tone",Map.of());
        var costlyNodes=new ArrayList<>(costly.nodes()); costlyNodes.add(n("dense",NodeType.EUCLID,Map.of("steps",64.0,"pulses",64.0)));
        costlyNodes.add(n("fast",NodeType.FAST,Map.of("factor",2.0)));
        var costlyEdges=costly.edges().stream().map(e -> e.fromNode().equals("tone") ? new Graph.Edge("fast",e.fromPort(),e.toNode(),e.toPort()) : e).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        costlyEdges.add(Graph.edge("tone","dense")); costlyEdges.add(Graph.edge("dense","fast"));
        invalid(() -> GraphCompiler.compile(new Graph(3,costlyNodes,costlyEdges)));
    }
    private static Graph without(Graph graph,Set<String> removed) {
        return new Graph(3,graph.nodes().stream().filter(n -> !removed.contains(n.id())).toList(),
                graph.edges().stream().filter(e -> !removed.contains(e.fromNode()) && !removed.contains(e.toNode())).toList());
    }
    private static void multipleSourceLifecycle() {
        var ref=groove.engine.samples.FactorySamples.ref("factory:basic/kick.wav");
        Graph graph=multipleGraph();
        graph=new Graph(3,graph.nodes().stream().map(n -> n.id().equals("right")
                ? new Graph.Node("right",NodeType.GENERATOR_SAMPLE,Map.of("pitchRatio",.25,"pan",1.0),ref) : n).toList(),graph.edges());
        float[] pcm=new float[48000]; Arrays.fill(pcm,.1f);
        var bank=Map.of(ref,new groove.engine.samples.SampleData(48000,1,pcm));
        var program=new LiveRenderer.Program(state(graph,0,0,120),GraphCompiler.compile(graph),bank);
        var timeline=new LiveRenderer.Timeline(program,null);
        LiveRenderer a=new LiveRenderer(),b=new LiveRenderer(); a.publish(timeline); b.publish(timeline);
        float[] x=new float[256],y=new float[256];
        // Both late joins must reconstruct sample tails and fractional tone arcs in all source windows.
        timeline.prepare(10_250_000_000L);
        a.render(x,128,10_250_000_000L); b.render(y,128,10_250_000_000L);
        check(Arrays.equals(x,y) && x[255]>0,"Independent sources resolve sample bank and late-join tails");
        for(int block=1;block<900;block++) {
            long now=10_250_000_000L+Math.round(block*128*1e9/48000);
            timeline.prepare(now); a.render(x,128,now); b.render(y,128,now);
            check(Arrays.equals(x,y),"Shared programs retain private source voices/effects");
        }
        check(a.scheduleMisses()==0 && b.scheduleMisses()==0,"Worker refills every source across cycle rollover");
        a.render(x,128,40_000_000_000L);
        check(Arrays.equals(x,new float[256]) && a.scheduleMisses()>0,"Missing source coverage silences the whole route");
        timeline.prepare(40_000_000_000L); a.resynchronize();
        LiveRenderer fresh=new LiveRenderer(); fresh.publish(timeline);
        a.render(x,128,40_000_000_000L); fresh.render(y,128,40_000_000_000L);
        check(Arrays.equals(x,y),"Recovery resets every source and its routing state");
        timeline.prepare(2_000_000_000L); a.resynchronize(); fresh.resynchronize();
        a.render(x,128,2_000_000_000L); fresh.render(y,128,2_000_000_000L);
        check(Arrays.equals(x,y),"Backward seek prepares every independent source");
        var changed=state(graph,3_000_000_000L,1.5,180);
        var pending=new LiveRenderer.Program(changed,GraphCompiler.compile(graph),bank);
        var revised=new LiveRenderer.Timeline(program,pending); revised.prepare(3_000_000_000L);
        a.publish(revised); fresh.publish(revised);
        a.render(x,128,3_000_000_000L); fresh.render(y,128,3_000_000_000L);
        check(Arrays.equals(x,y),"Pending tempo revision includes all independent sources");
    }
    /** Step-1 plumbing only: TRIGGER_RENDER/ENVELOPE compile, schedule and propagate live-renderer
     *  coverage/resync correctly. ENVELOPE's own evaluated output isn't trigger-aware yet (step 2). */
    private static void triggerRenderPlumbing() {
        Graph graph = base(List.of(n("trig",NodeType.TRIGGER_RENDER,Map.of()),n("env",NodeType.ENVELOPE,Map.of()),
                n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","trig"),new Graph.Edge("trig","out","env","trigger"),
                        Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        SignalGraph signals = GraphCompiler.compile(graph).signals();
        check(signals.triggerCount()==1 && signals.triggerNodeId(0).equals("trig"),"Stable trigger mapping");
        // Interleave the trigger before its source/effect nodes to prove ordinals aren't node indices.
        Graph interleaved = new Graph(3, List.of(n("out",NodeType.OUTPUT,Map.of()),n("trig",NodeType.TRIGGER_RENDER,Map.of()),
                n("tone",NodeType.TONE,Map.of()),n("env",NodeType.ENVELOPE,Map.of()),n("render",NodeType.AUDIO_RENDER,Map.of()),
                n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","trig"),new Graph.Edge("trig","out","env","trigger"),
                        Graph.edge("tone","render"),Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        check(GraphCompiler.compile(interleaved).signals().triggerNodeId(0).equals("trig"),"Trigger ordinal independent of node order");
        // Live scheduling: the trigger child must be prepared, captured and propagate schedule misses like a source child.
        var program = new LiveRenderer.Program(state(graph,0,0,120),GraphCompiler.compile(graph));
        var timeline = new LiveRenderer.Timeline(program,null);
        LiveRenderer live = new LiveRenderer(); live.publish(timeline);
        float[] x = new float[256];
        timeline.prepare(0); live.render(x,128,0);
        check(live.scheduleMisses()==0,"Trigger window prepared alongside audio sources");
        live.render(x,128,40_000_000_000L);
        check(Arrays.equals(x,new float[256]) && live.scheduleMisses()>0,"Uncovered trigger window silences the whole route");
        timeline.prepare(40_000_000_000L); live.resynchronize();
        LiveRenderer fresh = new LiveRenderer(); fresh.publish(timeline);
        float[] y = new float[256];
        live.render(x,128,40_000_000_000L); fresh.render(y,128,40_000_000_000L);
        check(Arrays.equals(x,y),"Recovery resets the trigger child alongside every audio source");
        // Cap: exceeding MAX_TRIGGER_SOURCES is rejected before reachability is even checked.
        var tooManyNodes = new ArrayList<Graph.Node>();
        for (int i=0;i<SignalGraph.MAX_TRIGGER_SOURCES+1;i++) tooManyNodes.add(n("trig"+i,NodeType.TRIGGER_RENDER,Map.of()));
        invalid(() -> GraphCompiler.compile(base(tooManyNodes,List.of())));
        // Exactly at the cap, fully wired through its own filter chain, still compiles.
        var maxNodes = new ArrayList<Graph.Node>(); var maxEdges = new ArrayList<Graph.Edge>();
        String filterChain = "render";
        for (int i=0;i<SignalGraph.MAX_TRIGGER_SOURCES;i++) {
            String tid="trig"+i, eid="env"+i, fid="filter"+i;
            maxNodes.add(n(tid,NodeType.TRIGGER_RENDER,Map.of())); maxNodes.add(n(eid,NodeType.ENVELOPE,Map.of()));
            maxNodes.add(n(fid,NodeType.FILTER,Map.of()));
            maxEdges.add(Graph.edge("tone",tid)); maxEdges.add(new Graph.Edge(tid,"out",eid,"trigger"));
            maxEdges.add(Graph.edge(filterChain,fid)); maxEdges.add(new Graph.Edge(eid,"out",fid,"cutoff"));
            filterChain = fid;
        }
        maxEdges.add(out(filterChain));
        check(GraphCompiler.compile(base(maxNodes,maxEdges)).signals().triggerCount()==SignalGraph.MAX_TRIGGER_SOURCES,"Maximum trigger source count compiles");
    }
    private static long cycleNanos(double cycle,double bpm) { return Math.round(cycle*240e9/bpm); }
    /** Manually primes a single-trigger-source graph's window, mirroring what LiveRenderer's
     *  capture()/sample() do automatically, so evaluate()'s TRIGGER_RENDER branch has a real
     *  window to scan when reached directly through SignalRuntime instead of LiveRenderer. */
    private static void primeTrigger(SignalRuntime dsp,SignalGraph signals,double cycle,double bpm) {
        var scheduler = new LookaheadScheduler(signals.triggerPlan(0).pattern(),SignalGraph.MAX_ENVELOPE_TAIL_CYCLES);
        scheduler.prepare(cycle);
        dsp.process(new double[signals.sourceCount()][2],new LookaheadScheduler.Window[]{scheduler.window()},new double[2],cycleNanos(cycle,bpm));
    }
    private static void arbitraryTriggerEnvelope() {
        // Regression: LookaheadScheduler's audio-tuned retention filter discards a non-sample
        // event once its whole arc ends more than ~1 cycle before the queried base, regardless
        // of historyCycles, unless the trigger constructor opts out via retainAllEvents. Assert
        // directly at the scheduler level that a short (~1/8-cycle) onset from several cycles
        // back is still present, since going through the full periodic-pattern envelope math
        // makes isolating one specific historical onset awkward (patterns repeat every cycle).
        Graph sparse = base(List.of(n("euclid",NodeType.EUCLID,Map.of("steps",8.0,"pulses",1.0)),
                n("trig",NodeType.TRIGGER_RENDER,Map.of()),n("env",NodeType.ENVELOPE,Map.of()),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","euclid"),Graph.edge("euclid","trig"),new Graph.Edge("trig","out","env","trigger"),
                        Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        var sparseSignals = GraphCompiler.compile(sparse).signals();
        var scheduler = new LookaheadScheduler(sparseSignals.triggerPlan(0).pattern(),SignalGraph.MAX_ENVELOPE_TAIL_CYCLES);
        scheduler.prepare(5.0);
        boolean sawOldOnset = false;
        for (int e=0;e<scheduler.window().size();e++)
            if (scheduler.window().entry(e).event().whole().start() <= 2.0) sawOldOnset = true;
        check(sawOldOnset,"Trigger scheduler retains a short non-sample onset several cycles back, not just ~1 cycle");
        // A EUCLID(4,4) firing every step is isochronous at period 1/4, identical to a
        // STEP_SEQUENCE(steps=1,rate=4) trigger in ONE_SHOT mode: same onset timing means the
        // same age/releaseAt math, so the two graphs' mixed audio should be bit-identical.
        Map<String,Double> envParams = Map.of("attack",.02,"decay",.05,"sustain",.4,"release",.03,"mode",0.0);
        Graph arbitrary = base(List.of(n("euclid",NodeType.EUCLID,Map.of("steps",4.0,"pulses",4.0)),
                n("trig",NodeType.TRIGGER_RENDER,Map.of()),n("env",NodeType.ENVELOPE,envParams),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","euclid"),Graph.edge("euclid","trig"),new Graph.Edge("trig","out","env","trigger"),
                        Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        Graph reference = base(List.of(n("seq",NodeType.STEP_SEQUENCE,Map.of("steps",1.0,"rate",4.0)),
                n("env",NodeType.ENVELOPE,envParams),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(new Graph.Edge("seq","trigger","env","trigger"),
                        Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        float[] fromTrigger = renderFrames(arbitrary,4000,137), fromSequence = renderFrames(reference,4000,137);
        for (int i=0;i<fromTrigger.length;i++) close(fromTrigger[i],fromSequence[i],1e-6,"Arbitrary pattern trigger matches an equivalent periodic step sequence");
        // Overlap: a still-releasing earlier voice must not be clobbered by a fresh, quieter
        // attack. Compare the full two-onset graph against an onset-2-only variant at a point
        // where onset 1 is still decaying and onset 2 has barely begun; if MAX combine is
        // working, the full graph's value must exceed the onset-2-only graph's value, since
        // onset 2's own contribution is identical in both.
        Map<String,Double> overlapParams = Map.of("attack",.01,"decay",.05,"sustain",.9,"release",.3,"mode",0.0);
        Graph both = base(List.of(n("euclid",NodeType.EUCLID,Map.of("steps",8.0,"pulses",8.0)),
                n("trig",NodeType.TRIGGER_RENDER,Map.of()),n("env",NodeType.ENVELOPE,overlapParams),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","euclid"),Graph.edge("euclid","trig"),new Graph.Edge("trig","out","env","trigger"),
                        Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        Graph onset2Only = replace(both,"euclid",Map.of("steps",8.0,"pulses",1.0,"rotation",1.0));
        SignalGraph bothSignals = GraphCompiler.compile(both).signals(), onset2Signals = GraphCompiler.compile(onset2Only).signals();
        SignalRuntime bothDsp = bothSignals.runtime(state(both,0,0,120)), onset2Dsp = onset2Signals.runtime(state(onset2Only,0,0,120));
        double cycle = .13;
        primeTrigger(bothDsp,bothSignals,cycle,120); primeTrigger(onset2Dsp,onset2Signals,cycle,120);
        double bothValue = bothDsp.control("env",cycleNanos(cycle,120)), onset2Value = onset2Dsp.control("env",cycleNanos(cycle,120));
        check(bothValue > onset2Value + .1,"Still-releasing voice's contribution survives a newer, quieter attack: "+bothValue+" vs "+onset2Value);
        // Late join / backward seek determinism: evaluate() must stay a pure function of the
        // window, not carry state forward, since PHASE-2-SIGNALS.md promises a fresh client at
        // the same time sees the same control value.
        SignalRuntime freshDsp = bothSignals.runtime(state(both,0,0,120));
        primeTrigger(freshDsp,bothSignals,cycle,120);
        close(freshDsp.control("env",cycleNanos(cycle,120)),bothValue,0,"Late join matches an already-running client at the same cycle");
        double earlierCycle = .05;
        primeTrigger(bothDsp,bothSignals,earlierCycle,120);
        double rewound = bothDsp.control("env",cycleNanos(earlierCycle,120));
        check(rewound != bothValue,"Sanity: the two probed cycles are not coincidentally equal");
        primeTrigger(bothDsp,bothSignals,cycle,120);
        close(bothDsp.control("env",cycleNanos(cycle,120)),bothValue,0,"Backward seek then forward re-evaluation reproduces the same value");
    }
    private static float[] renderFrames(Graph graph,int frames,int chunk) {
        var timeline=new LiveRenderer.Timeline(new LiveRenderer.Program(state(graph,0,0,120),GraphCompiler.compile(graph)),null);
        LiveRenderer renderer=new LiveRenderer(); renderer.publish(timeline);
        float[] output=new float[frames*2];
        for(int at=0;at<frames;at+=chunk) {
            int size=Math.min(chunk,frames-at); float[] buffer=new float[size*2];
            long now=Math.round(at*1e9/48000); timeline.prepare(now); renderer.render(buffer,size,now);
            System.arraycopy(buffer,0,output,at*2,buffer.length);
        }
        check(renderer.scheduleMisses()==0,"All source schedulers prepared");
        return output;
    }
    private static void allocation() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return;
        allocation.setThreadAllocatedMemoryEnabled(true);
        Graph g=SignalDemo.graph(); SignalRuntime dsp=runtime(g,state(g,0,0,120)); double[] frame=new double[2];
        for (int i=0;i<200_000;i++) { frame[0]=.1; frame[1]=.2; dsp.process(frame,Math.round(i*1e9/48000)); }
        long id=Thread.currentThread().threadId(), before=allocation.getThreadAllocatedBytes(id);
        for (int i=200_000;i<328_000;i++) { frame[0]=.1; frame[1]=.2; dsp.process(frame,Math.round(i*1e9/48000)); }
        long bytes=allocation.getThreadAllocatedBytes(id)-before;
        check(bytes==0,"Signal render allocated "+bytes+" bytes");
        Graph multi=multipleGraph();
        var program = new LiveRenderer.Program(state(multi,0,0,120),GraphCompiler.compile(multi));
        var renderer = new LiveRenderer(); renderer.publish(new LiveRenderer.Timeline(program,null));
        float[] block = new float[128];
        for (int i=0;i<2000;i++) renderer.render(block,64,Math.round(i*64*1e9/48000));
        before=allocation.getThreadAllocatedBytes(id);
        for (int i=2000;i<4000;i++) renderer.render(block,64,Math.round(i*64*1e9/48000));
        bytes=allocation.getThreadAllocatedBytes(id)-before;
        check(bytes==0 && renderer.scheduleMisses()==0,"Live signal renderer allocation/starvation: "+bytes);
        Graph triggered = base(List.of(n("euclid",NodeType.EUCLID,Map.of("steps",4.0,"pulses",4.0)),
                n("trig",NodeType.TRIGGER_RENDER,Map.of()),n("env",NodeType.ENVELOPE,Map.of()),n("mix",NodeType.MIX_BUS,Map.of())),
                List.of(Graph.edge("tone","euclid"),Graph.edge("euclid","trig"),new Graph.Edge("trig","out","env","trigger"),
                        Graph.edge("render","mix"),new Graph.Edge("env","out","mix","gain"),out("mix")));
        var triggerProgram = new LiveRenderer.Program(state(triggered,0,0,120),GraphCompiler.compile(triggered));
        var triggerRenderer = new LiveRenderer(); triggerRenderer.publish(new LiveRenderer.Timeline(triggerProgram,null));
        for (int i=0;i<2000;i++) triggerRenderer.render(block,64,Math.round(i*64*1e9/48000));
        before=allocation.getThreadAllocatedBytes(id);
        for (int i=2000;i<4000;i++) triggerRenderer.render(block,64,Math.round(i*64*1e9/48000));
        bytes=allocation.getThreadAllocatedBytes(id)-before;
        check(bytes==0 && triggerRenderer.scheduleMisses()==0,"Trigger-render envelope allocation/starvation: "+bytes);
    }
    private static void close(double actual,double expected,double tolerance,String message) { check(Math.abs(actual-expected)<=tolerance,message+": "+actual+" != "+expected); }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected invalid signal graph"); }
}

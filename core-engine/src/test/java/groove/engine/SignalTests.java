package groove.engine;

import java.util.*;

final class SignalTests {
    static void run() {
        validation(); modulation(); feedback(); filter(); live(); allocation();
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
        var program = new LiveRenderer.Program(state(g,0,0,120),GraphCompiler.compile(g));
        var renderer = new LiveRenderer(); renderer.publish(new LiveRenderer.Timeline(program,null));
        float[] block = new float[128];
        for (int i=0;i<2000;i++) renderer.render(block,64,Math.round(i*64*1e9/48000));
        before=allocation.getThreadAllocatedBytes(id);
        for (int i=2000;i<4000;i++) renderer.render(block,64,Math.round(i*64*1e9/48000));
        bytes=allocation.getThreadAllocatedBytes(id)-before;
        check(bytes==0 && renderer.scheduleMisses()==0,"Live signal renderer allocation/starvation: "+bytes);
    }
    private static void close(double actual,double expected,double tolerance,String message) { check(Math.abs(actual-expected)<=tolerance,message+": "+actual+" != "+expected); }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected invalid signal graph"); }
}

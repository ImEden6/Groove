package groove.engine;

import groove.engine.samples.*;
import java.util.*;

/** Regions must be isolated before prefiltering, bounded, and identical across rendering paths. */
final class SampleRegionTests {
    private static final AssetRef REF = FactorySamples.ref("factory:basic/kick.wav");
    static void run() {
        boundaries(); memory(); validation(); rendering(); signalSources(); offlineSteal(); voiceLifetimes(); allocation();
        System.out.println("Sample region, reverse, offline/live parity, bounds and allocation regressions passed.");
    }
    private static void boundaries() {
        float[] values = new float[22];
        for (int i=0;i<11;i++) { values[i*2] = i/20f; values[i*2+1] = -i/20f; }
        SampleData pcm = new SampleData(8000,2,values);
        int cursor=0;
        for(int slice=0;slice<4;slice++) {
            SampleRegion r = new SampleRegion(0,0,4,slice,false);
            check(r.start(pcm)==cursor,"Slices cover the original without gaps");
            int end=r.end(pcm);
            SampleData copy=pcm.copyRegion(cursor,end,false), reverse=pcm.copyRegion(cursor,end,true);
            for(int f=0;f<copy.frames();f++) for(int c=0;c<2;c++) {
                check(copy.at(f,c)==values[(cursor+f)*2+c],"Exact forward region frames");
                check(reverse.at(f,c)==values[(end-1-f)*2+c],"Reverse preserves frame/channel alignment");
            }
            cursor=end;
        }
        check(cursor==pcm.frames(),"Remainder frames retained by final slice");
        check(pcm.copyRegion(0,pcm.frames(),false)==pcm,"Whole forward assets are reused");
        invalid(() -> new SampleRegion(0,0,12,0,false).start(pcm));
        invalid(() -> new SampleRegion(8,7,1,0,false));
        invalid(() -> new SampleRegion(0,0,4,4,false));
        invalid(() -> new SampleRegion(0,12,1,0,false).start(pcm));
        // Both neighboring regions contain loud material; the selected one is silent.
        float[] neighbors = new float[4096]; Arrays.fill(neighbors,.9f); Arrays.fill(neighbors,1024,2048,0);
        SampleData adjacent = new SampleData(48000,1,neighbors);
        for(boolean reverse : new boolean[]{false,true}) {
            SampleVoice voice=voice(1,new SampleRegion(1024,2048,1,0,reverse));
            SamplePlayback prepared=voice.prepare(adjacent);
            for(double step : new double[]{.25,.5,1,1.999,2,4,15.999,16}) {
                for(double frame : new double[]{0,.1,5.5,511.25,1023.9})
                    check(prepared.pcm().at(frame,0,step)==0,"No neighboring-slice bleed at any resampling level");
            }
        }
        SampleVoice cropped=voice(2,new SampleRegion(2,10,2,1,true));
        SamplePlayback playback=cropped.prepare(pcm);
        check(playback.pcm().frames()==4 && playback.duration()==4/16000.0,"Region duration includes pitch");
        check(playback.value(-1,0,48000)==0 && playback.value(playback.duration(),0,48000)==0,"Half-open sample lifetime");
        invalid(() -> cropped.value(pcm,.1,0));
    }
    private static void memory() {
        SampleData pcm = new SampleData(48000,1,new float[4096]);
        SampleVoice a=voice(1,new SampleRegion(1024,2048,1,0,false));
        SampleVoice b=new SampleVoice(REF,2,.2,.5,1000,1,a.region());
        long budget=pcm.bytes()+SampleData.storageBytes(1024,1);
        PreparedSamples bank=new PreparedSamples(Map.of(REF,pcm),List.of(a,b),budget);
        check(bank.bytes()==budget && bank.get(a).pcm()==bank.get(b).pcm(),"Region PCM shared across pitch/gain/filter settings");
        check(bank.get(a).pcm().bytes()==SampleData.storageBytes(1024,1),"Memory estimate includes every resampling level");
        invalid(() -> new PreparedSamples(Map.of(REF,pcm),List.of(a),budget-1));
        invalid(() -> new PreparedSamples(Map.of(REF,pcm),List.of(),pcm.bytes()-1));
        var variants=new ArrayList<SampleVoice>();
        for(int i=0;i<129;i++) variants.add(voice(.25+i/100.0,SampleRegion.ALL));
        invalid(() -> new PreparedSamples(Map.of(REF,pcm),variants));
        SampleVoice reverse=voice(1,new SampleRegion(1024,2048,1,0,true));
        invalid(() -> new PreparedSamples(Map.of(REF,pcm),List.of(a,reverse),budget));
    }
    private static void validation() {
        SampleData pcm=new SampleData(48000,1,new float[4096]);
        Graph valid=graph(1,false);
        GraphCompiler.compile(valid);
        invalid(() -> GraphCompiler.compile(replace(valid,"slice",Map.of("slices",4.0,"index",4.0))));
        invalid(() -> GraphCompiler.compile(replace(valid,"slice",Map.of("slices",0.0))));
        invalid(() -> GraphCompiler.compile(replace(valid,"slice",Map.of("reverse",.5))));
        invalid(() -> GraphCompiler.compile(replace(valid,"sample",Map.of("startFrame",100.5))));
        invalid(() -> GraphCompiler.compile(replace(valid,"sample",Map.of("startFrame",200.0,"endFrame",100.0))));
        Graph toneInput=new Graph(3,List.of(n("sample",NodeType.TONE,Map.of()),n("slice",NodeType.SAMPLE_SLICE,Map.of()),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("sample","slice"),Graph.edge("slice","out")));
        invalid(() -> GraphCompiler.compile(toneInput));
        Graph later=new Graph(3,List.of(sampleNode("good",Map.of()),sampleNode("bad",Map.of("startFrame",5000.0)),
                n("alt",NodeType.ALTERNATE,Map.of()),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("good","alt"),Graph.edge("bad","alt"),Graph.edge("alt","out")));
        LoopPlan plan=GraphCompiler.compile(later);
        check(plan.size()==1,"Invalid later asset region is absent from first-cycle preview");
        invalid(() -> new LiveRenderer.Program(state(later),plan,Map.of(REF,pcm)));
        Pattern sample=Pattern.sample(new SampleVoice(REF,1,.4,0));
        invalid(() -> Score.compile(sample,new Transport(48000,120,4),1));
        SampleData highRate=new SampleData(192000,1,new float[1000]);
        invalid(() -> Score.compile(Pattern.sample(new SampleVoice(REF,4,.4,0)),new Transport(8000,120,4),1,Map.of(REF,highRate)));
        Pattern sliced=sample.slice(4,2,true).fast(3);
        List<Event> full=sliced.query(new Arc(-2,3));
        for(int i=0;i<80;i++) {
            Arc part=new Arc(-2+i/16.0,-2+(i+1)/16.0);
            List<Event> expected=new ArrayList<>();
            for(Event e:full) { Arc p=e.part().intersect(part); if(p!=null) expected.add(new Event(e.whole(),p,null,e.sample())); }
            check(sliced.query(part).equals(expected),"Slice query partition equivalence");
        }
    }
    private static void rendering() {
        SampleData pcm=fixture();
        for(boolean reverse:new boolean[]{false,true}) for(double ratio:new double[]{.25,1.25,4}) {
            Graph graph=graph(ratio,reverse);
            LoopPlan plan=GraphCompiler.compile(graph);
            Map<AssetRef,SampleData> bank=new HashMap<>(Map.of(REF,pcm));
            Score score=Score.compile(plan.pattern(),new Transport(48000,120,4),3,bank);
            LiveRenderer.Program program=new LiveRenderer.Program(state(graph),plan,bank);
            bank.clear(); // Prepared renderers own their resolved references.
            int frames=(int)score.frames();
            float[] expected=new float[frames*2],chunked=new float[frames*2];
            new Renderer(score,32).render(expected,0,frames);
            Renderer pieces=new Renderer(score,32);
            for(int at=0;at<frames;at+=127) pieces.render(chunked,at,Math.min(127,frames-at));
            check(Arrays.equals(expected,chunked),"Offline mixed samples are block-size independent");
            LiveRenderer live=new LiveRenderer(); live.publish(new LiveRenderer.Timeline(program,null));
            float[] block=new float[1024]; double maxError=0,energy=0;
            for(int at=0;at<frames;at+=512) {
                int count=Math.min(512,frames-at); long now=Math.round(at*1e9/48000);
                program.prepare(now); live.render(block,count,now);
                for(int i=0;i<count*2;i++) {
                    if(at*2+i>=1000) maxError=Math.max(maxError,Math.abs(block[i]-expected[at*2+i]));
                    energy+=block[i]*block[i];
                }
            }
            check(maxError<.001,"Live/offline sample parity, reverse="+reverse+", ratio="+ratio+", error="+maxError);
            check(energy>1 && live.scheduleMisses()==0,"Mixed sample playback stays audible and scheduled");
        }
        SampleVoice shortVoice=voice(2,new SampleRegion(0,2400,1,0,false));
        Score shortScore=Score.compile(Pattern.sample(shortVoice),new Transport(48000,120,4),1,Map.of(REF,pcm));
        check(shortScore.note(0).end()==2400,"Offline note lifetime comes from region, not event arc or whole asset");
        float[] audio=new float[24000]; new Renderer(shortScore,1).render(audio,0,audio.length/2);
        for(int i=6000;i<audio.length;i++) check(audio[i]==0,"Region ends without playing the next slice");
        // An alternating branch not visible in the preview still plays after a seek/late join.
        Graph alternating=alternatingGraph(); LoopPlan alt=GraphCompiler.compile(alternating);
        var program=new LiveRenderer.Program(state(alternating),alt,Map.of(REF,pcm));
        LiveRenderer live=new LiveRenderer(); live.publish(new LiveRenderer.Timeline(program,null));
        for(long now:new long[]{2_100_000_000L,8_100_000_000L,100_000_000L}) {
            program.prepare(now); live.resynchronize(); live.render(audio,1000,now);
            double energy=0; for(int i=500;i<2000;i++) energy+=audio[i]*audio[i];
            check(energy>.01 && live.scheduleMisses()==0,"Prepared later region survives late join and backward seek");
        }
        // Concurrent current/pending programs share one region budget, including duplicate sources.
        var bare=new LiveRenderer.Timeline(new LiveRenderer.Program(state(alternating),alt),new LiveRenderer.Program(state(alternating),alt));
        var bound=LiveRenderer.Timeline.withSamples(bare,Map.of(REF,pcm));
        long expectedBytes=pcm.bytes()+2*SampleData.storageBytes(pcm.frames()/2,pcm.channels());
        check(bound.current().preparedSampleBytes()==expectedBytes && bound.pending().preparedSampleBytes()==expectedBytes,"Timeline uses one deduplicated region bank");
    }
    private static void allocation() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(!(bean instanceof com.sun.management.ThreadMXBean counter) || !counter.isThreadAllocatedMemorySupported()) return;
        counter.setThreadAllocatedMemoryEnabled(true);
        SampleData pcm=fixture(); Graph graph=graph(.25,true); LoopPlan plan=GraphCompiler.compile(graph);
        var program=new LiveRenderer.Program(state(graph),plan,Map.of(REF,pcm));
        LiveRenderer live=new LiveRenderer(); live.publish(new LiveRenderer.Timeline(program,null));
        Renderer offline=new Renderer(Score.compile(plan.pattern(),new Transport(48000,120,4),4,Map.of(REF,pcm)),32);
        float[] block=new float[128];
        for(int i=0;i<2000;i++) { live.render(block,64,Math.round(i*64*1e9/48000)); offline.render(block,0,64); }
        long id=Thread.currentThread().threadId(),before=counter.getThreadAllocatedBytes(id);
        for(int i=2000;i<4000;i++) { live.render(block,64,Math.round(i*64*1e9/48000)); offline.render(block,0,64); }
        long bytes=counter.getThreadAllocatedBytes(id)-before;
        check(bytes==0 && live.scheduleMisses()==0,"Sample callbacks allocate zero bytes across 2,000 64-frame blocks: "+bytes);
    }
    private static void voiceLifetimes() {
        float[] shortPcm = new float[2400]; Arrays.fill(shortPcm, 0.5f);
        SampleData shortData = new SampleData(48000, 1, shortPcm);
        SampleVoice shortV = new SampleVoice(REF, 1, 0.8, 0, 20000, Biquad.DEFAULT_Q);
        Score scoreShort = Score.compile(Pattern.sample(shortV), new Transport(48000, 120, 4), 1, Map.of(REF, shortData));
        check(scoreShort.note(0).end() == 2400, "Short sample note end uses sample duration, not event arc");
        float[] outShort = new float[9600 * 2];
        new Renderer(scoreShort, 1).render(outShort, 0, 9600);
        for (int i = 2400 + 120; i < 9600; i++) {
            check(outShort[i * 2] == 0 && outShort[i * 2 + 1] == 0, "Short sample voice ends without lingering to event arc end");
        }
        float[] longPcm = new float[96000]; Arrays.fill(longPcm, 0.5f);
        SampleData longData = new SampleData(48000, 1, longPcm);
        SampleVoice longV = new SampleVoice(REF, 1, 0.8, 0, 20000, Biquad.DEFAULT_Q);
        Score scoreLong = Score.compile(Pattern.sample(longV).fast(4), new Transport(48000, 120, 4), 1, Map.of(REF, longData));
        check(scoreLong.note(0).end() == 96000, "Long sample note end extends to sample duration across fast event arcs");
        float[] outLong = new float[30000 * 2];
        new Renderer(scoreLong, 4).render(outLong, 0, 30000);
        // Each arc is 24,000 frames, so a voice truncated at its arc would leave one voice sounding throughout.
        check(outLong[12000 * 2] > 0 && outLong[26000 * 2] > outLong[12000 * 2] * 1.5,
                "Long sample voice outlives its event arc and stacks with the next trigger");
        float[] resonantPcm = new float[1000]; Arrays.fill(resonantPcm, 0.8f);
        SampleData resonantData = new SampleData(48000, 1, resonantPcm);
        SampleVoice resonantVoice = new SampleVoice(REF, 1, 1.0, 0, 500, 10.0);
        Score resonantScore = Score.compile(Pattern.sample(resonantVoice), new Transport(48000, 120, 4), 1, Map.of(REF, resonantData));
        float[] resonantOut = new float[2000 * 2];
        new Renderer(resonantScore, 1).render(resonantOut, 0, 2000);
        double tailEnergy = 0;
        for (int i = 1000; i < 1120; i++) tailEnergy += resonantOut[i * 2] * resonantOut[i * 2];
        check(tailEnergy > 1e-6, "Resonant sample filter rings out into steal fade rather than hard cutting to zero");
        check(resonantOut[1200 * 2] == 0, "Resonant filter finishes cleanly after steal fade");
        Graph g = graph(1.0, false);
        LoopPlan plan = GraphCompiler.compile(g);
        var prog = new LiveRenderer.Program(state(g), plan, Map.of(REF, fixture()));
        LiveRenderer live = new LiveRenderer();
        live.publish(new LiveRenderer.Timeline(prog, null));
        float[] liveBuf = new float[1024];
        prog.prepare(3_000_000_000L);
        live.resynchronize();
        live.render(liveBuf, 512, 3_000_000_000L);
        double liveEnergy = 0;
        for (float v : liveBuf) liveEnergy += v * v;
        check(liveEnergy > 0.01 && live.scheduleMisses() == 0, "Late join with cached duration renders audible sound without schedule miss");
    }
    private static void signalSources() {
        Graph graph=new Graph(3,List.of(sampleNode("sample",Map.of()),n("slice",NodeType.SAMPLE_SLICE,Map.of("slices",2.0,"index",1.0,"reverse",1.0)),
                n("a",NodeType.AUDIO_RENDER,Map.of()),n("b",NodeType.AUDIO_RENDER,Map.of()),n("mix",NodeType.MIX_BUS,Map.of("gain",.5)),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("sample","slice"),Graph.edge("slice","a"),Graph.edge("slice","b"),Graph.edge("a","mix"),Graph.edge("b","mix"),new Graph.Edge("mix","out","out","audio")));
        SampleData pcm=fixture();
        var program=new LiveRenderer.Program(state(graph),GraphCompiler.compile(graph),Map.of(REF,pcm));
        check(program.preparedSampleBytes()==pcm.bytes()+SampleData.storageBytes(pcm.frames()/2,2),"Independent audio sources reuse one isolated slice");
        LiveRenderer live=new LiveRenderer(); live.publish(new LiveRenderer.Timeline(program,null));
        float[] block=new float[4800]; live.render(block,2400,0);
        double energy=0; for(float value:block) energy+=value*value;
        check(energy>1 && live.scheduleMisses()==0,"Slice metadata reaches independent signal sources");
    }
    private static void offlineSteal() {
        float[] dc=new float[48000]; Arrays.fill(dc,.8f);
        SampleData pcm=new SampleData(48000,1,dc);
        SampleVoice loud=new SampleVoice(REF,1,.8,0,1000,1), quiet=new SampleVoice(REF,1,0,0);
        Pattern pattern=arc -> {
            List<Event> events=new ArrayList<>();
            Arc first=new Arc(0,1), second=new Arc(.025,1);
            Arc part=first.intersect(arc); if(part!=null) events.add(new Event(first,part,null,loud));
            part=second.intersect(arc); if(part!=null) events.add(new Event(second,part,null,quiet));
            return events;
        };
        Score score=Score.compile(pattern,new Transport(48000,120,4),1,Map.of(REF,pcm));
        Renderer renderer=new Renderer(score,1); float[] audio=new float[8000]; renderer.render(audio,0,4000);
        check(renderer.stolenVoices()==1 && Math.abs(audio[4798])>.2,"Offline sample voice is stolen while audible");
        for(int i=2400;i<2521;i++) check(Math.abs(audio[i*2]-audio[i*2-2])<.05,"Offline sample steal retains filter history through fade");
        check(audio[6000]==0,"Offline sample steal tail finishes");
    }
    private static SampleData fixture() {
        float[] pcm=new float[24000*2];
        for(int i=0;i<24000;i++) { pcm[i*2]=(float)(.4*Math.sin(2*Math.PI*211*i/24000)); pcm[i*2+1]=(float)(.3*Math.cos(2*Math.PI*137*i/24000)); }
        return new SampleData(24000,2,pcm);
    }
    private static Graph graph(double ratio,boolean reverse) {
        return new Graph(3,List.of(sampleNode("sample",Map.of("pitchRatio",ratio,"gain",.3,"startFrame",2400.0,"endFrame",21600.0)),
                n("slice",NodeType.SAMPLE_SLICE,Map.of("slices",3.0,"index",1.0,"reverse",reverse?1.0:0.0)),
                n("fast",NodeType.FAST,Map.of("factor",4.0)),n("tone",NodeType.TONE,Map.of("frequency",53.0,"gain",.1)),
                n("mix",NodeType.STACK,Map.of()),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("sample","slice"),Graph.edge("slice","fast"),Graph.edge("fast","mix"),Graph.edge("tone","mix"),Graph.edge("mix","out")));
    }
    private static Graph alternatingGraph() {
        return new Graph(3,List.of(sampleNode("sample",Map.of()),n("a",NodeType.SAMPLE_SLICE,Map.of("slices",2.0,"index",0.0)),
                n("b",NodeType.SAMPLE_SLICE,Map.of("slices",2.0,"index",1.0,"reverse",1.0)),n("alt",NodeType.ALTERNATE,Map.of()),n("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("sample","a"),Graph.edge("sample","b"),Graph.edge("a","alt"),Graph.edge("b","alt"),Graph.edge("alt","out")));
    }
    private static SampleVoice voice(double ratio,SampleRegion region) { return new SampleVoice(REF,ratio,.4,0,20000,Biquad.DEFAULT_Q,region); }
    private static Graph.Node sampleNode(String id,Map<String,Double> params) { return new Graph.Node(id,NodeType.GENERATOR_SAMPLE,params,REF); }
    private static Graph.Node n(String id,NodeType type,Map<String,Double> params) { return new Graph.Node(id,type,params); }
    private static Graph replace(Graph g,String id,Map<String,Double> params) {
        return new Graph(g.version(),g.nodes().stream().map(n -> n.id().equals(id)?new Graph.Node(id,n.type(),params,n.sample()):n).toList(),g.edges());
    }
    private static SessionState state(Graph graph) { return new SessionState(1,0,0,120,true,graph); }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
}

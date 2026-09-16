package groove.engine;

import java.util.*;

final class FilterModeTests {
    static void run() {
        check(response(Biquad.Mode.LOW_PASS,100) > .99 && response(Biquad.Mode.LOW_PASS,10000) < .01, "Low-pass response");
        check(response(Biquad.Mode.HIGH_PASS,100) < .011 && response(Biquad.Mode.HIGH_PASS,10000) > .99, "High-pass response");
        check(Math.abs(response(Biquad.Mode.BAND_PASS,1000)-1) < 1e-8
                && response(Biquad.Mode.BAND_PASS,100) < .15 && response(Biquad.Mode.BAND_PASS,10000) < .15, "Unity-peak band-pass response");
        check(response(Biquad.Mode.NOTCH,1000) < 1e-8 && response(Biquad.Mode.NOTCH,100) > .98, "Notch response");
        Biquad filter = new Biquad();
        for (Biquad.Mode mode : Biquad.Mode.values()) {
            for (double cutoff : new double[]{20,1000,20000,24000}) for (double q : new double[]{.1,Biquad.DEFAULT_Q,20}) {
                filter.reset(); filter.set(mode,cutoff,q,48000);
                for (int i=0;i<12000;i++) check(Double.isFinite(filter.process(i == 0 ? 1 : 0)), "Stable impulse at bounds");
            }
            Graph graph = graph(mode.ordinal());
            SignalRuntime runtime = GraphCompiler.compile(graph).signals().runtime(new SessionState(1,0,0,120,true,graph));
            filter.reset(); filter.set(mode,1000,Biquad.DEFAULT_Q,48000);
            double[] stereo = new double[2];
            for (int i=0;i<4096;i++) {
                double x = .1*Math.sin(i*.31);
                stereo[0] = x; stereo[1] = 0;
                runtime.process(stereo,Math.round(i*1e9/48000));
                check(Math.abs(stereo[0]-filter.process(x)) < 1e-12 && stereo[1] == 0, "Signal filter selects mode and isolates channels");
            }
            allocation(runtime);
        }
        // Only legacy low-pass bypasses at Nyquist; HPF must still remove DC.
        filter.reset(); filter.set(Biquad.Mode.HIGH_PASS,24000,Biquad.DEFAULT_Q,48000);
        double last=0;
        for (int i=0;i<24000;i++) last=filter.process(1);
        check(Math.abs(last)<1e-10,"High-pass near Nyquist does not bypass");
        invalid(() -> GraphCompiler.compile(graph(4)));
        invalid(() -> GraphCompiler.compile(graph(.5)));
        System.out.println("Filter mode response, stability, routing and allocation regressions passed.");
    }
    private static double response(Biquad.Mode mode,double hz) {
        Biquad f=new Biquad(); f.set(mode,1000,Biquad.DEFAULT_Q,48000);
        double energy=0;
        for(int i=0;i<48000;i++) {
            double y=f.process(Math.sin(2*Math.PI*hz*i/48000));
            if(i>=24000) energy+=y*y;
        }
        return Math.sqrt(energy/12000);
    }
    private static Graph graph(double mode) {
        return new Graph(3,List.of(new Graph.Node("tone",NodeType.TONE,Map.of()),new Graph.Node("render",NodeType.AUDIO_RENDER,Map.of()),
                new Graph.Node("filter",NodeType.FILTER,Map.of("mode",mode,"cutoffHz",1000.0)),new Graph.Node("out",NodeType.OUTPUT,Map.of())),
                List.of(Graph.edge("tone","render"),Graph.edge("render","filter"),new Graph.Edge("filter","out","out","audio")));
    }
    private static void allocation(SignalRuntime runtime) {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean counter) || !counter.isThreadAllocatedMemorySupported()) return;
        counter.setThreadAllocatedMemoryEnabled(true);
        double[] stereo={0,0};
        for(int i=0;i<20000;i++) runtime.process(stereo,i*21000L);
        long id=Thread.currentThread().threadId(),before=counter.getThreadAllocatedBytes(id);
        for(int i=0;i<20000;i++) runtime.process(stereo,i*21000L);
        long bytes=counter.getThreadAllocatedBytes(id)-before;
        check(bytes==0,"Filter callback allocated "+bytes+" bytes");
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
}

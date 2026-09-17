package groove.engine;

import java.util.List;

/** Opt-in long reverb renders, kept out of check by the runtime budget. */
public final class ReverbLongTests {
    public static void main(String[] args) {
        long start = System.nanoTime();
        List<Double> t60 = ReverbTests.t60Decay(List.of(1.0, 5.0, 20.0));
        if (!(t60.get(2) > t60.get(1) && t60.get(1) > t60.get(0))) throw new AssertionError("T60 not monotonic: " + t60);
        ReverbTests.peakGainAndModulatedStability(List.of(5.0, 10.0, 20.0));
        ReverbTests.feedbackConvergence(20.0);
        ReverbTests.noiseSoak(10);
        System.out.printf("Reverb long tests passed in %.1f s.%n", (System.nanoTime() - start) / 1e9);
    }
}

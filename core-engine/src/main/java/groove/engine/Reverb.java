package groove.engine;

import java.util.Arrays;

/**
 * Preallocated Dattorro (1997) figure-eight plate reverberator scaled from 29,761 Hz to 48,000 Hz.
 * All memory is preallocated in the constructor.
 * {@link #process(double, long, double[])} and {@link #reset()} perform zero memory allocation.
 */
public final class Reverb {
    public static final double SAMPLE_RATE = 48000.0;
    // Peak magnitude target of -1 dB
    private static final double PEAK_TARGET = 0.8912509381337456;
    // Cubic fit of the unscaled tap peak against g, within 0.05 dB over 0.1..20 s
    private static final double PEAK_C0 = 2.994545, PEAK_C1 = -0.311177, PEAK_C2 = 1.479595, PEAK_C3 = -0.836766;

    // Published lengths at 29,761 Hz scaled by 48000 / 29761 = 1.61284896 and rounded:
    // Input diffusers
    private static final int DIFF1_LEN = 229;
    private static final int DIFF2_LEN = 173;
    private static final int DIFF3_LEN = 611;
    private static final int DIFF4_LEN = 447;
    private static final double DIFF1_COEFF = 0.75;
    private static final double DIFF2_COEFF = 0.75;
    private static final double DIFF3_COEFF = 0.625;
    private static final double DIFF4_COEFF = 0.625;

    // Tank Half A
    private static final int MOD_A1_BASE = 1084;
    private static final int MOD_A1_BUF_LEN = 1148; // 1084 + 64 padding
    private static final double MOD_A1_COEFF = 0.70;
    private static final int DEL_A1_LEN = 7182;
    private static final int AP_A2_LEN = 2903;
    private static final double AP_A2_COEFF = 0.50;
    private static final int DEL_A2_LEN = 6000;

    // Tank Half B
    private static final int MOD_B1_BASE = 1464;
    private static final int MOD_B1_BUF_LEN = 1528; // 1464 + 64 padding
    private static final double MOD_B1_COEFF = 0.70;
    private static final int DEL_B1_LEN = 6801;
    private static final int AP_B2_LEN = 4284;
    private static final double AP_B2_COEFF = 0.50;
    private static final int DEL_B2_LEN = 5101;

    // Modulation parameters
    private static final double MOD_EXCURSION = 26.0;
    private static final long PERIOD_FRAMES_A = 48000; // 1.0 Hz at 48 kHz
    private static final long PERIOD_FRAMES_B = 68571; // ~0.7 Hz at 48 kHz

    // Predelay buffer holds 500 ms = 24,000 frames plus the write slot
    private static final int PREDELAY_CAPACITY = 24001;

    // Circular buffers
    private final double[] preDelayBuffer = new double[PREDELAY_CAPACITY];
    private final double[] diff1Buffer = new double[DIFF1_LEN];
    private final double[] diff2Buffer = new double[DIFF2_LEN];
    private final double[] diff3Buffer = new double[DIFF3_LEN];
    private final double[] diff4Buffer = new double[DIFF4_LEN];

    private final double[] modA1Buffer = new double[MOD_A1_BUF_LEN];
    private final double[] delA1Buffer = new double[DEL_A1_LEN];
    private final double[] apA2Buffer = new double[AP_A2_LEN];
    private final double[] delA2Buffer = new double[DEL_A2_LEN];

    private final double[] modB1Buffer = new double[MOD_B1_BUF_LEN];
    private final double[] delB1Buffer = new double[DEL_B1_LEN];
    private final double[] apB2Buffer = new double[AP_B2_LEN];
    private final double[] delB2Buffer = new double[DEL_B2_LEN];

    // Cursors
    private int preDelayCursor;
    private int diff1Cursor, diff2Cursor, diff3Cursor, diff4Cursor;
    private int modA1Cursor, delA1Cursor, apA2Cursor, delA2Cursor;
    private int modB1Cursor, delB1Cursor, apB2Cursor, delB2Cursor;

    // Filter states
    private double yBandwidth;
    private double yDampingA;
    private double yDampingB;

    // Tank feedback states
    private double outA;
    private double outB;

    // Cached parameter values
    private double decaySeconds = 1.8;
    private double dampingHz = 6000.0;
    private double bandwidthHz = 12000.0;
    private double preDelayMs = 0.0;

    // Cached coefficients
    private double b;
    private double d;
    private double g;
    private double gIn;
    private double kOut;
    private int preDelayFrames;

    private long snappedWrites;

    // LFO modulation state (evaluated once per 64-frame control block)
    private boolean modulation = true;
    private long controlBlock = Long.MIN_VALUE;
    private double modOffsetA;
    private double modOffsetB;

    public Reverb() {
        updateCoefficients();
    }

    public void setParams(double decaySeconds, double dampingHz, double bandwidthHz, double preDelayMs) {
        if (this.decaySeconds != decaySeconds || this.dampingHz != dampingHz
                || this.bandwidthHz != bandwidthHz || this.preDelayMs != preDelayMs) {
            this.decaySeconds = decaySeconds;
            this.dampingHz = dampingHz;
            this.bandwidthHz = bandwidthHz;
            this.preDelayMs = preDelayMs;
            updateCoefficients();
        }
    }

    public void setModulation(boolean modulation) {
        this.modulation = modulation;
        this.controlBlock = Long.MIN_VALUE;
    }

    public boolean isModulation() {
        return modulation;
    }

    private void updateCoefficients() {
        double maxCutoff = 0.45 * SAMPLE_RATE;
        double bw = Math.min(Math.max(200.0, bandwidthHz), maxCutoff);
        b = 1.0 - Math.exp(-2.0 * Math.PI * bw / SAMPLE_RATE);

        double damp = Math.min(Math.max(200.0, dampingHz), maxCutoff);
        d = Math.exp(-2.0 * Math.PI * damp / SAMPLE_RATE);

        double tau = 0.181353;
        double clampedDecay = Math.max(0.1, Math.min(20.0, decaySeconds));
        g = Math.pow(10.0, -3.0 * tau / clampedDecay);
        gIn = 1.0 - g;
        kOut = PEAK_TARGET / (((PEAK_C3 * g + PEAK_C2) * g + PEAK_C1) * g + PEAK_C0);

        preDelayFrames = (int) Math.round(Math.max(0.0, Math.min(500.0, preDelayMs)) * (SAMPLE_RATE / 1000.0));
        preDelayFrames = Math.min(preDelayFrames, PREDELAY_CAPACITY - 1);
    }

    public void reset() {
        Arrays.fill(preDelayBuffer, 0.0);
        Arrays.fill(diff1Buffer, 0.0);
        Arrays.fill(diff2Buffer, 0.0);
        Arrays.fill(diff3Buffer, 0.0);
        Arrays.fill(diff4Buffer, 0.0);

        Arrays.fill(modA1Buffer, 0.0);
        Arrays.fill(delA1Buffer, 0.0);
        Arrays.fill(apA2Buffer, 0.0);
        Arrays.fill(delA2Buffer, 0.0);

        Arrays.fill(modB1Buffer, 0.0);
        Arrays.fill(delB1Buffer, 0.0);
        Arrays.fill(apB2Buffer, 0.0);
        Arrays.fill(delB2Buffer, 0.0);

        preDelayCursor = 0;
        diff1Cursor = 0; diff2Cursor = 0; diff3Cursor = 0; diff4Cursor = 0;
        modA1Cursor = 0; delA1Cursor = 0; apA2Cursor = 0; delA2Cursor = 0;
        modB1Cursor = 0; delB1Cursor = 0; apB2Cursor = 0; delB2Cursor = 0;

        yBandwidth = 0.0;
        yDampingA = 0.0;
        yDampingB = 0.0;
        outA = 0.0;
        outB = 0.0;

        controlBlock = Long.MIN_VALUE;
        modOffsetA = 0.0;
        modOffsetB = 0.0;
    }

    /**
     * Processes one mono input sample at 48 kHz and writes wet stereo output into {@code outLR}.
     * Absolutely zero heap allocation.
     */
    public void process(double in, long absoluteFrame, double[] outLR) {
        // If input is non-finite, flush to 0
        if (!Double.isFinite(in)) in = 0.0;

        // LFO modulation evaluated once per 64-frame block
        long block = Math.floorDiv(absoluteFrame, 64);
        if (block != controlBlock) {
            if (modulation) {
                long frameA = Math.floorMod(block * 64, PERIOD_FRAMES_A);
                double phaseA = (double) frameA / PERIOD_FRAMES_A;
                modOffsetA = Math.sin(2.0 * Math.PI * phaseA) * MOD_EXCURSION;

                long frameB = Math.floorMod(block * 64, PERIOD_FRAMES_B);
                double phaseB = (double) frameB / PERIOD_FRAMES_B;
                modOffsetB = Math.sin(2.0 * Math.PI * phaseB) * MOD_EXCURSION;
            } else {
                modOffsetA = 0.0;
                modOffsetB = 0.0;
            }
            controlBlock = block;
        }

        // 1. Predelay
        double xPre;
        if (preDelayFrames <= 0) {
            xPre = in;
        } else {
            preDelayBuffer[preDelayCursor] = in;
            int readIdx = preDelayCursor - preDelayFrames;
            if (readIdx < 0) readIdx += PREDELAY_CAPACITY;
            xPre = preDelayBuffer[readIdx];
            preDelayCursor = (preDelayCursor + 1) % PREDELAY_CAPACITY;
        }

        // 2. Bandwidth input lowpass filter
        double xBw = b * xPre + (1.0 - b) * yBandwidth;
        if (Math.abs(xBw) < 1e-15) { if (xBw != 0) snappedWrites++; xBw = 0.0; }
        yBandwidth = xBw;

        // 3. Input diffusers (series of 4 allpass filters)
        // Diffuser 1
        double w1 = diff1Buffer[diff1Cursor];
        double v1 = xBw - DIFF1_COEFF * w1;
        double y1 = w1 + DIFF1_COEFF * v1;
        diff1Buffer[diff1Cursor] = snap(v1);
        diff1Cursor = (diff1Cursor + 1) % DIFF1_LEN;

        // Diffuser 2
        double w2 = diff2Buffer[diff2Cursor];
        double v2 = y1 - DIFF2_COEFF * w2;
        double y2 = w2 + DIFF2_COEFF * v2;
        diff2Buffer[diff2Cursor] = snap(v2);
        diff2Cursor = (diff2Cursor + 1) % DIFF2_LEN;

        // Diffuser 3
        double w3 = diff3Buffer[diff3Cursor];
        double v3 = y2 - DIFF3_COEFF * w3;
        double y3 = w3 + DIFF3_COEFF * v3;
        diff3Buffer[diff3Cursor] = snap(v3);
        diff3Cursor = (diff3Cursor + 1) % DIFF3_LEN;

        // Diffuser 4
        double w4 = diff4Buffer[diff4Cursor];
        double v4 = y3 - DIFF4_COEFF * w4;
        double xDiff = w4 + DIFF4_COEFF * v4;
        diff4Buffer[diff4Cursor] = snap(v4);
        diff4Cursor = (diff4Cursor + 1) % DIFF4_LEN;

        // 4. Tank inputs (cross-coupled feedback from opposite tank half)
        double inA = gIn * xDiff + outB;
        double inB = gIn * xDiff + outA;

        // === Tank Half A ===
        // Modulated allpass A1
        double delayA1 = MOD_A1_BASE + modOffsetA;
        double wMa1 = readHermite(modA1Buffer, modA1Cursor, delayA1, MOD_A1_BUF_LEN);
        double vMa1 = inA - MOD_A1_COEFF * wMa1;
        double yMa1 = wMa1 + MOD_A1_COEFF * vMa1;
        modA1Buffer[modA1Cursor] = snap(vMa1);
        modA1Cursor = (modA1Cursor + 1) % MOD_A1_BUF_LEN;

        // Delay A1
        double wDelA1 = delA1Buffer[delA1Cursor];
        delA1Buffer[delA1Cursor] = snap(yMa1);
        int curA1 = delA1Cursor;
        delA1Cursor = (delA1Cursor + 1) % DEL_A1_LEN;

        // Damping lowpass filter A
        double yDa = (1.0 - d) * wDelA1 + d * yDampingA;
        if (Math.abs(yDa) < 1e-15) { if (yDa != 0) snappedWrites++; yDa = 0.0; }
        yDampingA = yDa;

        // Decay multiplier 1
        double xApA2 = g * yDa;

        // Allpass A2
        double wApA2 = apA2Buffer[apA2Cursor];
        double vApA2 = xApA2 - AP_A2_COEFF * wApA2;
        double yApA2 = wApA2 + AP_A2_COEFF * vApA2;
        apA2Buffer[apA2Cursor] = snap(vApA2);
        int curApA2 = apA2Cursor;
        apA2Cursor = (apA2Cursor + 1) % AP_A2_LEN;

        // Delay A2
        double wDelA2 = delA2Buffer[delA2Cursor];
        delA2Buffer[delA2Cursor] = snap(yApA2);
        int curA2 = delA2Cursor;
        delA2Cursor = (delA2Cursor + 1) % DEL_A2_LEN;

        // Tank A output for feedback to Tank B
        outA = snap(g * wDelA2);

        // === Tank Half B ===
        // Modulated allpass B1
        double delayB1 = MOD_B1_BASE + modOffsetB;
        double wMb1 = readHermite(modB1Buffer, modB1Cursor, delayB1, MOD_B1_BUF_LEN);
        double vMb1 = inB - MOD_B1_COEFF * wMb1;
        double yMb1 = wMb1 + MOD_B1_COEFF * vMb1;
        modB1Buffer[modB1Cursor] = snap(vMb1);
        modB1Cursor = (modB1Cursor + 1) % MOD_B1_BUF_LEN;

        // Delay B1
        double wDelB1 = delB1Buffer[delB1Cursor];
        delB1Buffer[delB1Cursor] = snap(yMb1);
        int curB1 = delB1Cursor;
        delB1Cursor = (delB1Cursor + 1) % DEL_B1_LEN;

        // Damping lowpass filter B
        double yDb = (1.0 - d) * wDelB1 + d * yDampingB;
        if (Math.abs(yDb) < 1e-15) { if (yDb != 0) snappedWrites++; yDb = 0.0; }
        yDampingB = yDb;

        // Decay multiplier 1
        double xApB2 = g * yDb;

        // Allpass B2
        double wApB2 = apB2Buffer[apB2Cursor];
        double vApB2 = xApB2 - AP_B2_COEFF * wApB2;
        double yApB2 = wApB2 + AP_B2_COEFF * vApB2;
        apB2Buffer[apB2Cursor] = snap(vApB2);
        int curApB2 = apB2Cursor;
        apB2Cursor = (apB2Cursor + 1) % AP_B2_LEN;

        // Delay B2
        double wDelB2 = delB2Buffer[delB2Cursor];
        delB2Buffer[delB2Cursor] = snap(yApB2);
        int curB2 = delB2Cursor;
        delB2Cursor = (delB2Cursor + 1) % DEL_B2_LEN;

        // Tank B output for feedback to Tank A
        outB = snap(g * wDelB2);

        // === Output Taps ===
        // Left:
        // +429 in delay A1 (7182)
        // +4797 in delay A1 (7182)
        // -3085 in allpass B2 (4284)
        // +3219 in delay B2 (5101)
        // -3210 in delay B1 (6801)
        // -302 in allpass A2 (2903)
        // -1719 in delay A2 (6000)
        double tapL = tapRead(delA1Buffer, curA1, 429, DEL_A1_LEN)
                + tapRead(delA1Buffer, curA1, 4797, DEL_A1_LEN)
                - tapRead(apB2Buffer, curApB2, 3085, AP_B2_LEN)
                + tapRead(delB2Buffer, curB2, 3219, DEL_B2_LEN)
                - tapRead(delB1Buffer, curB1, 3210, DEL_B1_LEN)
                - tapRead(apA2Buffer, curApA2, 302, AP_A2_LEN)
                - tapRead(delA2Buffer, curA2, 1719, DEL_A2_LEN);

        // Right:
        // +569 in delay B1 (6801)
        // +5850 in delay B1 (6801)
        // -1981 in allpass A2 (2903)
        // +4311 in delay A2 (6000)
        // -3405 in delay A1 (7182)
        // -540 in allpass B2 (4284)
        // -195 in delay B2 (5101)
        double tapR = tapRead(delB1Buffer, curB1, 569, DEL_B1_LEN)
                + tapRead(delB1Buffer, curB1, 5850, DEL_B1_LEN)
                - tapRead(apA2Buffer, curApA2, 1981, AP_A2_LEN)
                + tapRead(delA2Buffer, curA2, 4311, DEL_A2_LEN)
                - tapRead(delA1Buffer, curA1, 3405, DEL_A1_LEN)
                - tapRead(apB2Buffer, curApB2, 540, AP_B2_LEN)
                - tapRead(delB2Buffer, curB2, 195, DEL_B2_LEN);

        outLR[0] = kOut * tapL;
        outLR[1] = kOut * tapR;
    }

    /** Zeroes stored feedback values too small to hear, before they decay into slow subnormals. */
    private double snap(double v) {
        if (Math.abs(v) < Biquad.DENORMAL_SNAP && v != 0) { snappedWrites++; return 0.0; }
        return v;
    }

    long snappedWrites() { return snappedWrites; }

    private double[][] stateBuffers() {
        return new double[][]{preDelayBuffer, diff1Buffer, diff2Buffer, diff3Buffer, diff4Buffer,
                modA1Buffer, delA1Buffer, apA2Buffer, delA2Buffer, modB1Buffer, delB1Buffer, apB2Buffer, delB2Buffer};
    }

    /** Tests only: fills every buffer and filter state with one value. */
    void seedState(double value) {
        for (double[] buf : stateBuffers()) Arrays.fill(buf, value);
        yBandwidth = yDampingA = yDampingB = outA = outB = value;
    }

    /** Tests only: every state value is finite and none is subnormal. */
    boolean stateClean() {
        for (double[] buf : stateBuffers()) for (double v : buf) if (!Biquad.clean(v)) return false;
        return Biquad.clean(yBandwidth) && Biquad.clean(yDampingA) && Biquad.clean(yDampingB)
                && Biquad.clean(outA) && Biquad.clean(outB);
    }

    /** Largest magnitude held in the tank, for headroom tests. Scans buffers, so never call per frame. */
    double tankPeak() {
        double peak = 0.0;
        for (double[] buf : new double[][]{modA1Buffer, delA1Buffer, apA2Buffer, delA2Buffer,
                modB1Buffer, delB1Buffer, apB2Buffer, delB2Buffer})
            for (double v : buf) peak = Math.max(peak, Math.abs(v));
        return Math.max(peak, Math.max(Math.abs(outA), Math.abs(outB)));
    }

    private static double tapRead(double[] buf, int cursor, int delay, int length) {
        int idx = cursor - delay;
        if (idx < 0) {
            idx = (idx % length + length) % length;
        }
        return buf[idx];
    }

    private static double readHermite(double[] buf, int cursor, double delay, int length) {
        double readPos = cursor - delay;
        while (readPos < 0) readPos += length;
        while (readPos >= length) readPos -= length;
        int i1 = (int) readPos;
        double f = readPos - i1;
        int i0 = i1 - 1;
        if (i0 < 0) i0 += length;
        int i2 = i1 + 1;
        if (i2 >= length) i2 -= length;
        int i3 = i1 + 2;
        if (i3 >= length) i3 -= length;

        double y0 = buf[i0], y1 = buf[i1], y2 = buf[i2], y3 = buf[i3];
        double c0 = y1;
        double c1 = 0.5 * (y2 - y0);
        double c2 = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3;
        double c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2);
        return ((c3 * f + c2) * f + c1) * f + c0;
    }
}

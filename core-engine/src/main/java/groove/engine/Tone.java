package groove.engine;

/** Immutable voice settings. Frequency is in Hz; pan is -1 (left) to +1. cutoffHz
 *  drives the voice's low-pass filter; upper-bound/Nyquist enforcement happens in
 *  Biquad.setLowPass, since Tone doesn't know the render sample rate. */
public record Tone(Wave wave, double frequency, double gain, double pan, double cutoffHz, double resonanceQ, double pulseWidth) {
    public Tone(Wave wave, double frequency, double gain, double pan, double cutoffHz) {
        this(wave, frequency, gain, pan, cutoffHz, Biquad.DEFAULT_Q, 0.5);
    }
    public enum Wave { SINE, SAW, PULSE }
    public Tone {
        if (wave == null || !Double.isFinite(frequency) || frequency <= 0
                || !Double.isFinite(gain) || gain < 0 || gain > 1
                || !Double.isFinite(pan) || Math.abs(pan) > 1
                || !Double.isFinite(resonanceQ) || resonanceQ < .1 || resonanceQ > 20
                || !Double.isFinite(cutoffHz) || cutoffHz <= 0
                || !Double.isFinite(pulseWidth) || pulseWidth < 0.01 || pulseWidth > 0.99)
            throw new IllegalArgumentException("Invalid tone settings");
    }
}

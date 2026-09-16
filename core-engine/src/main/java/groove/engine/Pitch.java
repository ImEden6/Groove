package groove.engine;

/** Control-thread pitch helpers. Scientific pitch notation uses C4 = MIDI 60, A4 = 440 Hz. */
public final class Pitch {
    private Pitch() {}

    public enum Scale {
        MAJOR(0,2,4,5,7,9,11), NATURAL_MINOR(0,2,3,5,7,8,10),
        DORIAN(0,2,3,5,7,9,10), PHRYGIAN(0,1,3,5,7,8,10),
        LYDIAN(0,2,4,6,7,9,11), MIXOLYDIAN(0,2,4,5,7,9,10),
        MINOR_PENTATONIC(0,3,5,7,10), BLUES(0,3,5,6,7,10), WHOLE_TONE(0,2,4,6,8,10);
        private final int[] intervals;
        Scale(int... intervals) { this.intervals = intervals; }
        public int semitones(int degree) {
            if (degree < -64 || degree > 64) throw new IllegalArgumentException("Degree must be -64..64");
            return 12 * Math.floorDiv(degree, intervals.length) + intervals[Math.floorMod(degree, intervals.length)];
        }
    }

    public enum Chord {
        MAJOR(0,4,7), MINOR(0,3,7), DOMINANT_7(0,4,7,10), MAJOR_7(0,4,7,11),
        MINOR_7(0,3,7,10), SUS4(0,5,7), DIMINISHED(0,3,6), DOMINANT_9(0,4,7,10,14);
        private final int[] intervals;
        Chord(int... intervals) { this.intervals = intervals; }
        public int size() { return intervals.length; }
        public int[] intervals(int inversion) {
            if (inversion < 0 || inversion >= size()) throw new IllegalArgumentException("Invalid chord inversion");
            int[] result = intervals.clone();
            for (int i = 0; i < inversion; i++) result[i] += 12;
            java.util.Arrays.sort(result);
            return result;
        }
    }

    public static int midi(String note) {
        if (note == null || !note.matches("[A-Ga-g][#b]?-?[0-9]"))
            throw new IllegalArgumentException("Expected a note name such as C4, F#4 or Bb2");
        int pitch = switch (Character.toUpperCase(note.charAt(0))) {
            case 'C' -> 0; case 'D' -> 2; case 'E' -> 4; case 'F' -> 5;
            case 'G' -> 7; case 'A' -> 9; default -> 11;
        };
        int at = 1;
        if (note.charAt(at) == '#' || note.charAt(at) == 'b') pitch += note.charAt(at++) == '#' ? 1 : -1;
        int midi = 12 * (Integer.parseInt(note.substring(at)) + 1) + pitch;
        if (midi < 0 || midi > 127) throw new IllegalArgumentException("Note must be within MIDI 0..127");
        return midi;
    }

    public static double hz(String note) { return hz(midi(note)); }
    public static double hz(int midi) {
        if (midi < 0 || midi > 127) throw new IllegalArgumentException("Pitch must be MIDI 0..127");
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }
    public static double degreeHz(int root, Scale scale, int degree) {
        if (scale == null || root < 0 || root > 127) throw new IllegalArgumentException("Invalid scale/root");
        return hz(root + scale.semitones(degree));
    }
    public static Tone withFrequency(Tone tone, double frequency) {
        if (tone == null) throw new IllegalArgumentException("Pitch transforms require tone events");
        if (!Double.isFinite(frequency) || frequency < 20 || frequency > 16000)
            throw new IllegalArgumentException("Transformed frequency must be 20..16000 Hz");
        return new Tone(tone.wave(), frequency, tone.gain(), tone.pan(), tone.cutoffHz(), tone.resonanceQ(), tone.pulseWidth());
    }
}

package groove.engine;

/** World values a WORLD node reads, each 0 to 1, written by the client and read by the sound thread. */
public final class WorldInputs {
    public static final int DAYLIGHT = 0, RAIN = 1, THUNDER = 2, ALTITUDE = 3, TEMPERATURE = 4, HUMIDITY = 5, PROXIMITY = 6;
    public static final int COUNT = 7;
    public static final String[] NAMES = {"Daylight", "Rain", "Thunder", "Altitude", "Temperature", "Humidity", "Proximity"};
    // Noon, dry, sea level in plains, at the speaker
    private static final double[] DEFAULTS = {1, 0, 0, (63 + 64) / 384.0, (0.8 + 0.7) / 2.7, 0.4, 1};

    private volatile double[] values = DEFAULTS;

    /** NaN keeps the previous value. */
    public void update(double[] next) {
        if (next.length != COUNT) throw new IllegalArgumentException("Expected " + COUNT + " world values");
        double[] was = values, copy = new double[COUNT];
        for (int i = 0; i < COUNT; i++) copy[i] = Double.isNaN(next[i]) ? was[i] : Math.max(0, Math.min(1, next[i]));
        values = copy;
    }

    public double get(int source) { return values[source]; }

    /** Vanilla biome temperatures run from -0.7 to 2.0. */
    public static double temperature(double biome) { return (biome + 0.7) / 2.7; }

    public static double proximity(double blocks) { return 1 - Math.min(1, Math.max(0, blocks) / 64); }
}

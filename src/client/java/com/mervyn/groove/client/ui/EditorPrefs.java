package com.mervyn.groove.client.ui;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Client-only editor display preferences, persisted under the game directory. A missing or
 *  unreadable file falls back to defaults, since these are conveniences, not authoritative state. */
public final class EditorPrefs {
    private static final String CABLE_PULSES = "cablePulses";
    private static Boolean cablePulses;

    private EditorPrefs() {}

    /** Whether cables show the marker that rides them on the beat. On by default. */
    public static boolean cablePulses() {
        if (cablePulses == null) cablePulses = readCablePulses(file());
        return cablePulses;
    }

    public static void setCablePulses(boolean on) {
        cablePulses = on;
        writeCablePulses(file(), on);
    }

    public static boolean readCablePulses(Path file) {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { props.load(reader); }
        catch (IOException | IllegalArgumentException error) { return true; }
        return !"false".equals(props.getProperty(CABLE_PULSES));
    }

    public static void writeCablePulses(Path file, boolean on) {
        Properties props = new Properties();
        props.setProperty(CABLE_PULSES, Boolean.toString(on));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) { props.store(writer, null); }
        } catch (IOException ignored) { /* Best-effort; the choice just won't persist. */ }
    }

    private static Path file() { return FabricLoader.getInstance().getGameDir().resolve("groove/editor-prefs.properties"); }
}

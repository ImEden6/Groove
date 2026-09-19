package com.mervyn.groove.music;

import com.mervyn.groove.client.ui.EditorPrefs;
import java.nio.file.*;

final class EditorPrefsTests {
    static void run() throws Exception {
        Path dir = Files.createTempDirectory("groove-prefs");
        Path file = dir.resolve("groove/editor-prefs.properties");
        check(EditorPrefs.readCablePulses(file), "Missing file keeps pulses on");
        EditorPrefs.writeCablePulses(file, false);
        check(!EditorPrefs.readCablePulses(file), "Pulses off survives a save");
        EditorPrefs.writeCablePulses(file, true);
        check(EditorPrefs.readCablePulses(file), "Pulses back on survives a save");
        Files.writeString(file, "cablePulses=\\u12");
        check(EditorPrefs.readCablePulses(file), "Corrupt file keeps pulses on");
        System.out.println("Editor preference checks passed.");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

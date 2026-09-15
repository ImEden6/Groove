package com.mervyn.groove.client.ui;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Client-only, per-player favorited sample ids for the drawer. Persisted as one asset id
 *  per line under the game directory; a missing or corrupt file behaves as "no favorites"
 *  rather than failing, since favorites are a convenience, not authoritative state. */
public final class SampleFavorites {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("groove/favorite-samples.txt");
    private static final Set<String> ids = load();

    private SampleFavorites() {}

    public static boolean isFavorite(String assetId) { return ids.contains(assetId); }
    public static Set<String> all() { return Collections.unmodifiableSet(ids); }

    public static void toggle(String assetId) {
        if (!ids.remove(assetId)) ids.add(assetId);
        save();
    }

    private static Set<String> load() {
        try { return new LinkedHashSet<>(Files.readAllLines(FILE)); }
        catch (IOException error) { return new LinkedHashSet<>(); }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, ids);
        } catch (IOException ignored) { /* Best-effort; favorites just won't persist this session. */ }
    }
}

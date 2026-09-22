package com.mervyn.groove.music;

import com.mervyn.groove.block.GrooveItems;
import groove.engine.SessionTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A Groove disc playing in a vanilla jukebox, as a session speaker towers standing on it play.
 * It starts when the server first sees the disc playing and restarts when the disc is changed or reinserted.
 */
public final class JukeboxSessions {
    private record Key(ResourceKey<Level> level, BlockPos pos) {}
    private record Entry(UUID session, long startTick, DiscPatches.Patch patch, SessionTimeline timeline, long polled) {}
    private static final Map<Key, Entry> sessions = new HashMap<>();
    private static final long FORGET_NANOS = 30_000_000_000L;

    /** What a tower on this jukebox plays, or null when it holds no playable Groove disc. */
    public static MusicPackets.Snapshot snapshot(ServerLevel level, BlockPos pos, long now) {
        sessions.values().removeIf(entry -> now - entry.polled > FORGET_NANOS);
        Key key = new Key(level.dimension(), pos.immutable());
        if (!(level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox) || !jukebox.getSongPlayer().isPlaying()
                || !jukebox.getTheItem().is(GrooveItems.GROOVE_DISC)) {
            sessions.remove(key);
            return null;
        }
        var patch = DiscPatches.read(jukebox.getTheItem()).orElse(null);
        long start = level.getGameTime() - jukebox.getSongPlayer().getTicksSinceSongStarted();
        Entry entry = sessions.get(key);
        if (entry == null || !entry.patch.equals(patch) || Math.abs(entry.startTick - start) > 1) {
            var graph = patch == null ? null : DiscPatches.graph(patch).orElse(null);
            if (graph == null) { sessions.remove(key); return null; }
            entry = new Entry(UUID.randomUUID(), start, patch, new SessionTimeline(graph, patch.bpm(), true, now), now);
        } else entry = new Entry(entry.session, entry.startTick, entry.patch, entry.timeline, now);
        sessions.put(key, entry);
        return new MusicPackets.Snapshot(entry.session, entry.timeline.snapshot(now));
    }

    /** The session a poll already started for this jukebox, while it still holds that disc; never starts one. */
    static SessionTimeline.Snapshot playing(ServerLevel level, BlockPos pos, long now) {
        Entry entry = sessions.get(new Key(level.dimension(), pos.immutable()));
        if (entry == null || !(level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox) || !jukebox.getSongPlayer().isPlaying()
                || !entry.patch.equals(DiscPatches.read(jukebox.getTheItem()).orElse(null))) return null;
        return entry.timeline.snapshot(now);
    }

    public static void clear() { sessions.clear(); }

    private JukeboxSessions() {}
}

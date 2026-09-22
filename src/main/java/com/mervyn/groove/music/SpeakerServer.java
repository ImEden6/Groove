package com.mervyn.groove.music;

import com.mervyn.groove.block.EditorBlockEntity;
import com.mervyn.groove.block.GrooveBlocks;
import com.mervyn.groove.block.SpeakerBlockEntity;
import groove.engine.samples.AssetRef;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Speaker binding lives in the editor's GUI; see docs/EDITOR-BLOCK-DESIGN.md's Speakers section. */
public final class SpeakerServer {
    private static final int SEARCH_RADIUS = 64;
    private record PollKey(UUID player, BlockPos pos) {}
    private static final Map<UUID, Long> listRequests = new HashMap<>();
    private static final Map<UUID, Long> bindRequests = new HashMap<>();
    private static final Map<PollKey, Long> committedPolls = new HashMap<>();

    public static void register() {
        SpeakerPackets.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            listRequests.clear();
            bindRequests.clear();
            committedPolls.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            listRequests.remove(id);
            bindRequests.remove(id);
            committedPolls.keySet().removeIf(k -> k.player().equals(id));
        });
        ServerPlayNetworking.registerGlobalReceiver(SpeakerPackets.ListRequest.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            if (!throttleList(player)) {
                ServerPlayNetworking.send(player, new SpeakerPackets.ListState(packet.request(), false, "Please wait before refreshing", List.of(), List.of()));
                return;
            }
            try {
                requireEditor(player, packet.editorPos(), packet.session());
                var speakers = nearbySpeakers(player.serverLevel(), packet.editorPos());
                var positions = new ArrayList<BlockPos>(speakers.size());
                var linked = new ArrayList<Boolean>(speakers.size());
                for (BlockPos pos : speakers) {
                    positions.add(pos);
                    var speaker = (SpeakerBlockEntity) player.serverLevel().getBlockEntity(pos);
                    if (speaker != null) linkedEditor(player.serverLevel(), speaker);
                    linked.add(speaker != null && speaker.linkedTo(packet.editorPos(), packet.session()));
                }
                ServerPlayNetworking.send(player, new SpeakerPackets.ListState(packet.request(), true, "", positions, linked));
            } catch (IllegalArgumentException error) {
                ServerPlayNetworking.send(player, new SpeakerPackets.ListState(packet.request(), false, error.getMessage(), List.of(), List.of()));
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(SpeakerPackets.BindRequest.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            boolean accepted = false;
            String message;
            try {
                if (!throttleBind(player)) throw new IllegalArgumentException("Please wait before submitting again");
                requireEditor(player, packet.editorPos(), packet.session());
                // Resolve to the tower base so the base segment holds the link, matching client audio polling.
                BlockPos target = packet.speakerPos();
                if (!player.serverLevel().hasChunk(target.getX() >> 4, target.getZ() >> 4) || target.distSqr(packet.editorPos()) > (long) SEARCH_RADIUS * SEARCH_RADIUS)
                    throw new IllegalArgumentException("Speaker is out of reach");
                if (!player.serverLevel().getBlockState(target).is(GrooveBlocks.SPEAKER))
                    throw new IllegalArgumentException("Not a speaker block");
                while (player.serverLevel().getBlockState(target.below()).is(GrooveBlocks.SPEAKER))
                    target = target.below();
                if (target.distSqr(packet.editorPos()) > (long) SEARCH_RADIUS * SEARCH_RADIUS)
                    throw new IllegalArgumentException("Speaker is too far from the editor");
                if (!(player.serverLevel().getBlockEntity(target) instanceof SpeakerBlockEntity speaker))
                    throw new IllegalArgumentException("Not a speaker block");
                if (packet.link()) speaker.bind(packet.editorPos(), packet.session());
                else if (speaker.linkedTo(packet.editorPos(), packet.session())) speaker.unlink();
                else throw new IllegalArgumentException("Speaker was relinked; refresh the list");
                accepted = true;
                message = packet.link() ? "Speaker linked" : "Speaker unlinked";
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
            ServerPlayNetworking.send(player, new SpeakerPackets.BindState(packet.request(), accepted, message));
        }));
        ServerPlayNetworking.registerGlobalReceiver(SpeakerPackets.CommittedRequest.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            long now = System.nanoTime();
            // Bound both read access and bookkeeping before touching world state.
            if (player.distanceToSqr(packet.pos().getCenter()) > (long) SEARCH_RADIUS * SEARCH_RADIUS
                    || !player.serverLevel().hasChunk(packet.pos().getX() >> 4, packet.pos().getZ() >> 4)) {
                ServerPlayNetworking.send(player, new SpeakerPackets.CommittedState(packet.pos(), packet.request(), false, null));
                return;
            }
            if (!allowPoll(player.getUUID(), packet.pos(), now)) return;
            EditorBlockEntity editor = null;
            var level = player.serverLevel();
            if (level.getBlockEntity(packet.pos()) instanceof SpeakerBlockEntity speaker
                    && !level.getBlockState(packet.pos().below()).is(GrooveBlocks.SPEAKER)) {
                // A tower standing on a jukebox plays its disc instead of any linked editor
                var disc = level.getBlockState(packet.pos().below()).is(net.minecraft.world.level.block.Blocks.JUKEBOX)
                        ? JukeboxSessions.snapshot(level, packet.pos().below(), now) : null;
                if (disc != null) {
                    ServerPlayNetworking.send(player, new SpeakerPackets.CommittedState(packet.pos(), packet.request(), true, disc));
                    return;
                }
                editor = linkedEditor(level, speaker);
            }
            boolean available = isAvailable(editor != null ? editor.project() : null);
            ServerPlayNetworking.send(player, new SpeakerPackets.CommittedState(packet.pos(), packet.request(), available,
                    available ? new MusicPackets.Snapshot(editor.sessionId(), editor.session().committed(now)) : null));
        }));
    }

    public static boolean isAvailable(EditorProject project) {
        return project != null && !project.isUnreadable();
    }

    static boolean allowPoll(UUID player, BlockPos pos, long now) {
        committedPolls.entrySet().removeIf(entry -> now - entry.getValue() > 5_000_000_000L);
        PollKey key = new PollKey(player, pos.immutable());
        Long last = committedPolls.get(key);
        if (last != null && now - last < 250_000_000L) return false;
        if (last == null && committedPolls.keySet().stream().filter(k -> k.player().equals(player)).count() >= 32) return false;
        committedPolls.put(key, now);
        return true;
    }
    private static EditorBlockEntity linkedEditor(ServerLevel level, SpeakerBlockEntity speaker) {
        if (!speaker.linked() || !level.hasChunk(speaker.editorPos().getX() >> 4, speaker.editorPos().getZ() >> 4)) return null;
        if (level.getBlockEntity(speaker.editorPos()) instanceof EditorBlockEntity editor && editor.sessionId().equals(speaker.editorSession())) return editor;
        // Catch stale links whose speaker chunk was unloaded when the editor was broken.
        speaker.unlink();
        return null;
    }
    private static boolean throttleList(ServerPlayer player) {
        long now = System.nanoTime();
        Long last = listRequests.get(player.getUUID());
        if (last != null && now - last < 250_000_000L) return false;
        listRequests.put(player.getUUID(), now);
        return true;
    }

    private static boolean throttleBind(ServerPlayer player) {
        long now = System.nanoTime();
        Long last = bindRequests.get(player.getUUID());
        if (last != null && now - last < 250_000_000L) return false;
        bindRequests.put(player.getUUID(), now);
        return true;
    }

    private static void requireEditor(ServerPlayer player, BlockPos pos, UUID session) {
        if (player.isSpectator()) throw new IllegalArgumentException("Spectators cannot bind speakers");
        if (!player.serverLevel().hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || player.distanceToSqr(pos.getCenter()) > 64)
            throw new IllegalArgumentException("Editor is out of reach");
        if (!(player.serverLevel().getBlockEntity(pos) instanceof EditorBlockEntity editor) || !editor.sessionId().equals(session))
            throw new IllegalArgumentException("Editor session changed; reopen it");
    }

    private static List<BlockPos> nearbySpeakers(ServerLevel level, BlockPos center) {
        var found = new ArrayList<BlockPos>();
        forEachNearbySpeaker(level, center, speaker -> {
            BlockPos pos = speaker.getBlockPos();
            if (!level.getBlockState(pos.below()).is(GrooveBlocks.SPEAKER)) {
                found.add(pos);
            }
        });
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.size() > SpeakerPackets.MAX_LISTED ? found.subList(0, SpeakerPackets.MAX_LISTED) : found;
    }

    private static void forEachNearbySpeaker(ServerLevel level, BlockPos center, Consumer<SpeakerBlockEntity> action) {
        int chunkRadius = (SEARCH_RADIUS >> 4) + 1;
        ChunkPos centerChunk = new ChunkPos(center);
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                int chunkX = centerChunk.x + dx, chunkZ = centerChunk.z + dz;
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (var entry : level.getChunk(chunkX, chunkZ).getBlockEntities().entrySet())
                    if (entry.getValue() instanceof SpeakerBlockEntity speaker && entry.getKey().distSqr(center) <= (long) SEARCH_RADIUS * SEARCH_RADIUS)
                        action.accept(speaker);
            }
        }
    }

    /** Returns whether a nearby linked speaker is currently playing a committed patch referencing this asset. */
    static boolean allowsAsset(ServerPlayer player, AssetRef ref) {
        var level = player.serverLevel();
        long now = System.nanoTime();
        // Audio clients poll before downloading. Revalidate those bounded recent
        // positions instead of scanning 121 chunks for every 16 KiB asset chunk.
        for (var entry : committedPolls.entrySet()) {
            BlockPos pos = entry.getKey().pos();
            if (!entry.getKey().player().equals(player.getUUID()) || now - entry.getValue() > 5_000_000_000L
                    || player.distanceToSqr(pos.getCenter()) > (long) SEARCH_RADIUS * SEARCH_RADIUS || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)
                    || level.getBlockState(pos.below()).is(GrooveBlocks.SPEAKER)) continue;
            var disc = JukeboxSessions.playing(level, pos.below(), now);
            if (disc != null) {
                if (references(disc, ref)) return true;
                continue;
            }
            var editor = linkedEditor(level, speaker);
            if (editor == null) continue;
            if (editor.project().preservesAsset(ref)) return true;
            if (editor.project().isUnreadable()) continue;
            var snapshot = editor.session().committed(now);
            if (references(snapshot, ref)) return true;
        }
        return false;
    }
    static boolean references(groove.engine.SessionTimeline.Snapshot snapshot, AssetRef ref) {
        return snapshot.current().graph().nodes().stream().anyMatch(n -> ref.equals(n.sample()))
                || snapshot.pending() != null && snapshot.pending().graph().nodes().stream().anyMatch(n -> ref.equals(n.sample()));
    }

    /** Called when an editor block is actually destroyed so its speaker links don't dangle. */
    public static void unlinkAll(ServerLevel level, BlockPos editorPos, UUID session) {
        forEachNearbySpeaker(level, editorPos, speaker -> { if (speaker.linkedTo(editorPos, session)) speaker.unlink(); });
    }

    private SpeakerServer() {}
}

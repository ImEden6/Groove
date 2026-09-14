package com.mervyn.groove.music;

import com.mervyn.groove.block.EditorBlockEntity;
import com.mervyn.groove.block.SpeakerBlockEntity;
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
    private static final Map<UUID, Long> requests = new HashMap<>();
    private static final Map<UUID, Long> committedPolls = new HashMap<>();

    public static void register() {
        SpeakerPackets.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { requests.clear(); committedPolls.clear(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { requests.remove(handler.player.getUUID()); committedPolls.remove(handler.player.getUUID()); });
        ServerPlayNetworking.registerGlobalReceiver(SpeakerPackets.ListRequest.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            if (!throttle(player)) return;
            try {
                requireEditor(player, packet.editorPos(), packet.session());
                var speakers = nearbySpeakers(player.serverLevel(), packet.editorPos());
                var positions = new ArrayList<BlockPos>(speakers.size());
                var linked = new ArrayList<Boolean>(speakers.size());
                for (BlockPos pos : speakers) {
                    positions.add(pos);
                    var speaker = (SpeakerBlockEntity) player.serverLevel().getBlockEntity(pos);
                    linked.add(speaker.linkedTo(packet.editorPos(), packet.session()));
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
                if (!throttle(player)) throw new IllegalArgumentException("Please wait before submitting again");
                requireEditor(player, packet.editorPos(), packet.session());
                if (packet.speakerPos().distSqr(packet.editorPos()) > (long) SEARCH_RADIUS * SEARCH_RADIUS)
                    throw new IllegalArgumentException("Speaker is too far from the editor");
                if (!(player.serverLevel().getBlockEntity(packet.speakerPos()) instanceof SpeakerBlockEntity speaker))
                    throw new IllegalArgumentException("Not a speaker block");
                if (packet.link()) speaker.bind(packet.editorPos(), packet.session()); else speaker.unlink();
                accepted = true;
                message = packet.link() ? "Speaker linked" : "Speaker unlinked";
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
            ServerPlayNetworking.send(player, new SpeakerPackets.BindState(packet.request(), accepted, message));
        }));
        ServerPlayNetworking.registerGlobalReceiver(SpeakerPackets.CommittedRequest.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            long now = System.nanoTime();
            Long last = committedPolls.get(player.getUUID());
            if (last != null && now - last < 250_000_000L) return;
            committedPolls.put(player.getUUID(), now);
            EditorBlockEntity editor = null;
            if (player.serverLevel().getBlockEntity(packet.pos()) instanceof SpeakerBlockEntity speaker && speaker.linked()
                    && player.serverLevel().getBlockEntity(speaker.editorPos()) instanceof EditorBlockEntity found
                    && found.sessionId().equals(speaker.editorSession())) {
                editor = found;
            }
            if (editor == null) {
                ServerPlayNetworking.send(player, new SpeakerPackets.CommittedState(packet.pos(), packet.request(), false, "", 128, false, 0, 0, 0));
                return;
            }
            long nowNanos = System.nanoTime();
            var state = editor.session().committed(nowNanos).at(nowNanos);
            ServerPlayNetworking.send(player, new SpeakerPackets.CommittedState(packet.pos(), packet.request(), true,
                    GraphJson.encode(state.graph()), state.bpm(), state.playing(), state.revision(), state.effectiveNanos(), state.anchorCycle()));
        }));
    }

    private static boolean throttle(ServerPlayer player) {
        long now = System.nanoTime();
        Long last = requests.get(player.getUUID());
        if (last != null && now - last < 250_000_000L) return false;
        requests.put(player.getUUID(), now);
        return true;
    }

    private static void requireEditor(ServerPlayer player, BlockPos pos, UUID session) {
        if (!player.serverLevel().hasChunkAt(pos) || player.distanceToSqr(pos.getCenter()) > 64)
            throw new IllegalArgumentException("Editor is out of reach");
        if (!(player.serverLevel().getBlockEntity(pos) instanceof EditorBlockEntity editor) || !editor.sessionId().equals(session))
            throw new IllegalArgumentException("Editor session changed; reopen it");
    }

    private static List<BlockPos> nearbySpeakers(ServerLevel level, BlockPos center) {
        var found = new ArrayList<BlockPos>();
        forEachNearbySpeaker(level, center, speaker -> found.add(speaker.getBlockPos()));
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

    /** Called when an editor block is actually destroyed so its speaker links don't dangle. */
    public static void unlinkAll(ServerLevel level, BlockPos editorPos, UUID session) {
        forEachNearbySpeaker(level, editorPos, speaker -> { if (speaker.linkedTo(editorPos, session)) speaker.unlink(); });
    }

    private SpeakerServer() {}
}

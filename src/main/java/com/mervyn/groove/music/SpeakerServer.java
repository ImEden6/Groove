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
                while (player.serverLevel().getBlockState(target.below()).is(GrooveBlocks.SPEAKER))
                    target = target.below();
                if (target.distSqr(packet.editorPos()) > (long) SEARCH_RADIUS * SEARCH_RADIUS)
                    throw new IllegalArgumentException("Speaker is too far from the editor");
                if (!(player.serverLevel().getBlockEntity(target) instanceof SpeakerBlockEntity speaker))
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
            PollKey pollKey = new PollKey(player.getUUID(), packet.pos());
            Long last = committedPolls.get(pollKey);
            if (last != null && now - last < 250_000_000L) return;
            committedPolls.put(pollKey, now);
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
        BlockPos playerPos = player.blockPosition();
        int chunkRadius = (SEARCH_RADIUS >> 4) + 1;
        ChunkPos centerChunk = new ChunkPos(playerPos);
        long nowNanos = System.nanoTime();
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                int chunkX = centerChunk.x + dx, chunkZ = centerChunk.z + dz;
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (var entry : level.getChunk(chunkX, chunkZ).getBlockEntities().entrySet()) {
                    if (entry.getValue() instanceof SpeakerBlockEntity speaker
                            && entry.getKey().distSqr(playerPos) <= (long) SEARCH_RADIUS * SEARCH_RADIUS
                            && speaker.linked()) {
                        if (level.getBlockEntity(speaker.editorPos()) instanceof EditorBlockEntity editor
                                && editor.sessionId().equals(speaker.editorSession())) {
                            var state = editor.session().committed(nowNanos).at(nowNanos);
                            if (state.graph().nodes().stream().anyMatch(n -> ref.equals(n.sample())))
                                return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Called when an editor block is actually destroyed so its speaker links don't dangle. */
    public static void unlinkAll(ServerLevel level, BlockPos editorPos, UUID session) {
        forEachNearbySpeaker(level, editorPos, speaker -> { if (speaker.linkedTo(editorPos, session)) speaker.unlink(); });
    }

    private SpeakerServer() {}
}

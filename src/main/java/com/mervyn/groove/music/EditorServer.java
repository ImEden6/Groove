package com.mervyn.groove.music;

import com.mervyn.groove.block.EditorBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import java.util.*;

public final class EditorServer {
    private static final Map<UUID, Long> requests = new HashMap<>();
    public static void register() {
        EditorPackets.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> requests.remove(handler.player.getUUID()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> requests.clear());
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                !(entity instanceof EditorBlockEntity editor) || editor.canEdit(player.getUUID()) || !editor.hasOwner() && player.hasPermissions(2));
        ServerPlayNetworking.registerGlobalReceiver(EditorPackets.Request.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            long now = System.nanoTime();
            Long last = requests.get(player.getUUID());

            EditorBlockEntity editor = null;
            boolean accepted = false;
            String message;
            try {
                if (last != null && now - last < 100_000_000L) throw new IllegalArgumentException("Please wait before submitting again");
                requests.put(player.getUUID(), now);
                if (packet.action() < EditorPackets.OPEN || packet.action() > EditorPackets.COMMIT)
                    throw new IllegalArgumentException("Unknown editor action");
                if (!player.serverLevel().hasChunkAt(packet.pos()) || player.distanceToSqr(packet.pos().getCenter()) > 64)
                    throw new IllegalArgumentException("Editor is out of reach");
                if (!(player.serverLevel().getBlockEntity(packet.pos()) instanceof EditorBlockEntity found))
                    throw new IllegalArgumentException("Editor no longer exists");
                editor = found;
                // Blocks placed before session support have no recorded placer.
                if (!editor.hasOwner() && player.hasPermissions(2) && packet.action() == EditorPackets.OPEN)
                    editor.setOwner(player.getUUID());
                if (!editor.canEdit(player.getUUID())) throw new IllegalArgumentException("Editing requires owner or allowlist access");
                if (packet.action() != EditorPackets.OPEN && !editor.sessionId().equals(packet.session()))
                    throw new IllegalArgumentException("Editor session changed; reopen it");
                if (packet.action() == EditorPackets.DRAFT) {
                    editor.session().edit(GraphJson.decodeDraft(packet.graph()), packet.bpm(), packet.playing());
                    editor.setChanged();
                } else if (packet.action() == EditorPackets.COMMIT) {
                    for (var node : editor.session().draft().nodes())
                        if (node.sample() != null && !SampleServer.hasAsset(node.sample()))
                            throw new IllegalArgumentException("Server lacks exact sample " + node.sample().assetId());
                    editor.session().commit(packet.revision(), now);
                    editor.setChanged();
                }
                accepted = true;
                message = packet.action() == EditorPackets.COMMIT ? "Committed for the safe downbeat" : "Draft saved";
            } catch (RuntimeException error) {
                message = error.getMessage() != null ? error.getMessage() : "Editor action failed";
            }
            boolean visible = editor != null && editor.canEdit(player.getUUID());
            var session = visible ? editor.session() : null;
            ServerPlayNetworking.send(player, new EditorPackets.State(packet.pos(), visible ? editor.sessionId() : packet.session(),
                    packet.request(), accepted, message.substring(0, Math.min(512, message.length())),
                    session == null ? 0 : session.revision(), session == null ? "" : GraphJson.encode(session.draft()),
                    session == null ? 128 : session.bpm(), session != null && session.playing()));
        }));
    }
}

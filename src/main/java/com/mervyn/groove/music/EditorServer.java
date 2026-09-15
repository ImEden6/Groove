package com.mervyn.groove.music;

import com.mervyn.groove.block.EditorBlockEntity;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class EditorServer {
    private static final Map<UUID, Long> requests = new HashMap<>();
    private static final Map<UUID, UUID> pendingLookups = new HashMap<>();
    /** Transient "who currently has this editor open" presence, not persisted and keyed by
     *  session so a replaced block's stale viewers never leak into its new session. */
    private record ViewerKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, net.minecraft.core.BlockPos pos, UUID session) {}
    private static final Map<ViewerKey, Set<UUID>> viewers = new HashMap<>();
    public static void register() {
        EditorPackets.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            requests.remove(id); pendingLookups.remove(id);
            removeViewer(id);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> { requests.clear(); pendingLookups.clear(); viewers.clear(); });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                !(entity instanceof EditorBlockEntity editor) || editor.canEdit(player.getUUID()) || !editor.hasOwner() && player.hasPermissions(2));
        ServerPlayNetworking.registerGlobalReceiver(EditorPackets.Request.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            if (packet.action() == EditorPackets.CLOSE) {
                var players = viewers.get(new ViewerKey(player.serverLevel().dimension(), packet.pos(), packet.session()));
                if (players != null) players.remove(player.getUUID());
                viewers.values().removeIf(Set::isEmpty);
                return; // Fire-and-forget teardown notice; the client is already closing.
            }
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
                if (!player.serverLevel().hasChunk(packet.pos().getX() >> 4, packet.pos().getZ() >> 4) || player.distanceToSqr(packet.pos().getCenter()) > 64)
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
                if (packet.action() == EditorPackets.OPEN) {
                    var key = new ViewerKey(player.serverLevel().dimension(), packet.pos(), editor.sessionId());
                    removeViewer(player.getUUID());
                    viewers.keySet().removeIf(k -> k.dimension().equals(key.dimension()) && k.pos().equals(packet.pos()) && !k.equals(key));
                    viewers.computeIfAbsent(key, k -> new HashSet<>()).add(player.getUUID());
                } else if (packet.action() == EditorPackets.DRAFT) {
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
                // Validation rejections are expected; retain diagnostics for unexpected failures.
                if (!(error instanceof IllegalArgumentException))
                    com.mervyn.groove.GrooveMod.LOGGER.error("Unexpected editor action failure at {} for player {}",
                            packet.pos(), player.getUUID(), error);
                message = error.getMessage() != null ? error.getMessage() : "Editor action failed";
            }
            boolean visible = editor != null && editor.canEdit(player.getUUID());
            var session = visible ? editor.session() : null;
            var otherViewers = new ArrayList<String>();
            if (visible) {
                var players = viewers.get(new ViewerKey(player.serverLevel().dimension(), packet.pos(), editor.sessionId()));
                if (players != null) {
                    // Revoked or displaced viewers must not accumulate beyond the packet's
                    // allowlist-sized bound, even when a client never sends CLOSE.
                    var iterator = players.iterator();
                    while (iterator.hasNext()) {
                        UUID id = iterator.next();
                        var viewer = context.server().getPlayerList().getPlayer(id);
                        if (viewer == null || !editor.canEdit(id) || viewer.serverLevel() != player.serverLevel()
                                || viewer.distanceToSqr(packet.pos().getCenter()) > 64) iterator.remove();
                        else if (!id.equals(player.getUUID())) otherViewers.add(viewer.getGameProfile().getName());
                    }
                    viewers.values().removeIf(Set::isEmpty);
                }
                otherViewers.sort(String.CASE_INSENSITIVE_ORDER);
            }
            ServerPlayNetworking.send(player, new EditorPackets.State(packet.pos(), visible ? editor.sessionId() : packet.session(),
                    packet.request(), accepted, message.substring(0, Math.min(512, message.length())),
                    session == null ? 0 : session.revision(), session == null ? "" : GraphJson.encode(session.draft()),
                    session == null ? 128 : session.bpm(), session != null && session.playing(), otherViewers));
            if (accepted && packet.action() == EditorPackets.OPEN) sendAllowlist(player, editor, UUID.randomUUID(), true, "");
        }));
        ServerPlayNetworking.registerGlobalReceiver(EditorPackets.AllowlistRequest.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            var server = context.server();
            try {
                requireAllowlistOwner(player, packet);
                long now = System.nanoTime();
                Long last = requests.get(player.getUUID());
                if (last != null && now - last < 100_000_000L || pendingLookups.containsKey(player.getUUID()))
                    throw new IllegalArgumentException("Please wait before submitting again");
                requests.put(player.getUUID(), now);
                String name = packet.username().trim();
                UUID target = null;
                if (!packet.allow()) {
                    try { target = UUID.fromString(name); } catch (IllegalArgumentException ignored) { }
                }
                if (target == null && !name.matches("[A-Za-z0-9_]{1,16}"))
                    throw new IllegalArgumentException("Enter a valid player name");
                var online = server.getPlayerList().getPlayerByName(name);
                if (target != null || online != null) {
                    finishAllowlist(player, packet, target != null ? target : online.getUUID(), null);
                    return;
                }
                // Profile cache misses may contact the authentication service. Never do that
                // on the server tick thread, and authorize again after the lookup completes.
                pendingLookups.put(player.getUUID(), packet.request());
                java.util.concurrent.CompletableFuture.supplyAsync(() -> server.getProfileCache() == null ? null
                        : server.getProfileCache().get(name).map(GameProfile::getId).orElse(null), net.minecraft.Util.backgroundExecutor())
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .whenComplete((id, error) -> server.execute(() -> {
                            if (!packet.request().equals(pendingLookups.get(player.getUUID()))) return;
                            pendingLookups.remove(player.getUUID());
                            if (server.getPlayerList().getPlayer(player.getUUID()) == player)
                                finishAllowlist(player, packet, id, error);
                        }));
            } catch (RuntimeException error) { rejectAllowlist(player, packet, error); }
        }));
    }
    private static void removeViewer(UUID id) {
        for (var players : viewers.values()) players.remove(id);
        viewers.values().removeIf(Set::isEmpty);
    }
    private static EditorBlockEntity requireAllowlistOwner(ServerPlayer player, EditorPackets.AllowlistRequest packet) {
        if (!player.serverLevel().hasChunk(packet.pos().getX() >> 4, packet.pos().getZ() >> 4) || player.distanceToSqr(packet.pos().getCenter()) > 64)
            throw new IllegalArgumentException("Editor is out of reach");
        if (!(player.serverLevel().getBlockEntity(packet.pos()) instanceof EditorBlockEntity editor)
                || !editor.sessionId().equals(packet.session()))
            throw new IllegalArgumentException("Editor session changed; reopen it");
        if (!player.getUUID().equals(editor.owner())) throw new IllegalArgumentException("Only the owner manages the allowlist");
        return editor;
    }
    private static void finishAllowlist(ServerPlayer player, EditorPackets.AllowlistRequest packet, UUID target, Throwable error) {
        try {
            var editor = requireAllowlistOwner(player, packet);
            if (error != null) throw new IllegalStateException("Player lookup failed; try again", error);
            if (target == null) throw new IllegalArgumentException("Unknown player: " + packet.username());
            editor.allowEditor(player.getUUID(), target, packet.allow());
            sendAllowlist(player, editor, packet.request(), true, (packet.allow() ? "Added " : "Removed ") + packet.username());
        } catch (RuntimeException failure) { rejectAllowlist(player, packet, failure); }
    }
    private static void rejectAllowlist(ServerPlayer player, EditorPackets.AllowlistRequest packet, RuntimeException error) {
        if (!(error instanceof IllegalArgumentException))
            com.mervyn.groove.GrooveMod.LOGGER.error("Unexpected allowlist failure at {} for player {}", packet.pos(), player.getUUID(), error);
        String message = error.getMessage() == null ? "Allowlist action failed" : error.getMessage();
        // Echo the requested identity so even missing/replaced blocks release the client's request.
        ServerPlayNetworking.send(player, new EditorPackets.AllowlistState(packet.pos(), packet.session(), packet.request(),
                false, message.substring(0, Math.min(512, message.length())), false, "", List.of()));
    }
    private static String resolveName(MinecraftServer server, UUID id) {
        var online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache() == null ? id.toString()
                : server.getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString());
    }
    private static void sendAllowlist(ServerPlayer player, EditorBlockEntity editor, UUID request, boolean accepted, String message) {
        boolean isOwner = editor.owner() != null && editor.owner().equals(player.getUUID());
        String ownerName = editor.owner() == null ? "" : resolveName(player.serverLevel().getServer(), editor.owner());
        var names = new ArrayList<String>();
        for (UUID id : editor.editors()) names.add(resolveName(player.serverLevel().getServer(), id));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        ServerPlayNetworking.send(player, new EditorPackets.AllowlistState(editor.getBlockPos(), editor.sessionId(), request,
                accepted, message, isOwner, ownerName, List.copyOf(names)));
    }
}

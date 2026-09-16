package com.mervyn.groove.music;

import com.mervyn.groove.block.EditorBlockEntity;
import com.mervyn.groove.block.GrooveItems;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Binding is open to listeners, but only the held headphones can be changed. */
public final class HeadphoneServer {
    private static final Map<UUID, Long> requests = new HashMap<>();
    private static final Map<UUID, Long> previews = new HashMap<>();
    public static void register() {
        HeadphonePackets.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { requests.clear(); previews.clear(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { requests.remove(handler.player.getUUID()); previews.remove(handler.player.getUUID()); });
        ServerPlayNetworking.registerGlobalReceiver(HeadphonePackets.Bind.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            var stack = player.getMainHandItem();
            boolean accepted = false;
            String message;
            try {
                long now = System.nanoTime();
                Long last = requests.get(player.getUUID());
                if (last != null && now - last < 250_000_000L) throw new IllegalArgumentException("Please wait before binding again");
                requests.put(player.getUUID(), now);
                if (player.isSpectator() || !stack.is(GrooveItems.HEADPHONES))
                    throw new IllegalArgumentException("Hold the headphones in your main hand to bind them");
                if (!player.serverLevel().hasChunk(packet.pos().getX() >> 4, packet.pos().getZ() >> 4) || player.distanceToSqr(packet.pos().getCenter()) > 64)
                    throw new IllegalArgumentException("Editor is out of reach");
                if (!(player.serverLevel().getBlockEntity(packet.pos()) instanceof EditorBlockEntity editor))
                    throw new IllegalArgumentException("Not an editor block");
                // Item components provide inventory persistence and synchronization without
                // rewriting a world-wide file or forcing disk I/O on the server tick thread.
                HeadphoneLinks.bind(stack, new HeadphoneLinks.Link(player.serverLevel().dimension().location(), packet.pos(), editor.sessionId()));
                player.inventoryMenu.broadcastChanges();
                accepted = true;
                message = "Headphones linked to " + packet.pos().getX() + ", " + packet.pos().getY() + ", " + packet.pos().getZ();
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
            var link = stack.is(GrooveItems.HEADPHONES) ? HeadphoneLinks.read(stack) : java.util.Optional.<HeadphoneLinks.Link>empty();
            ServerPlayNetworking.send(player, new HeadphonePackets.State(packet.request(), accepted, message,
                    link.isPresent(), link.map(HeadphoneLinks.Link::pos).orElse(BlockPos.ZERO)));
        }));
        ServerPlayNetworking.registerGlobalReceiver(HeadphonePackets.Preview.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            long now = System.nanoTime();
            Long last = previews.get(player.getUUID());
            if (last != null && now - last < 250_000_000L) return;
            previews.put(player.getUUID(), now);
            var editor = previewEditor(player);
            if (!isAvailable(editor != null ? editor.project() : null)) {
                ServerPlayNetworking.send(player, new HeadphonePackets.Draft(packet.request(), false, "", 128, false, 0, new UUID(0, 0), now, 0));
            } else {
                var state = editor.session().preview();
                ServerPlayNetworking.send(player, new HeadphonePackets.Draft(packet.request(), true, GraphJson.encode(state.graph()), state.bpm(),
                        state.playing(), state.revision(), editor.sessionId(), state.effectiveNanos(), state.anchorCycle()));
            }
        }));
        // Enforce unlinking even when a client stops sending preview requests.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerList().getPlayers()) previewEditor(player);
        });
    }
    public static boolean isAvailable(EditorProject project) {
        return project != null && !project.isUnreadable();
    }
    private static EditorBlockEntity previewEditor(net.minecraft.server.level.ServerPlayer player) {
        var worn = wornHeadphones(player);
        if (worn.isEmpty()) return null;
        var link = HeadphoneLinks.read(worn.get());
        if (link.isEmpty()) return null;
        var level = player.serverLevel();
        boolean inRange = HeadphoneLinks.inRange(link.get(), level.dimension().location(), player.position());
        if (inRange && !level.hasChunk(link.get().pos().getX() >> 4, link.get().pos().getZ() >> 4)) return null;
        EditorBlockEntity editor = inRange ? linkedEditor(level, link.get()) : null;
        if (editor != null) return player.isSpectator() ? null : editor;
        HeadphoneLinks.clear(worn.get());
        player.inventoryMenu.broadcastChanges();
        return null;
    }
    private static EditorBlockEntity linkedEditor(net.minecraft.server.level.ServerLevel level, HeadphoneLinks.Link link) {
        return level.getBlockEntity(link.pos()) instanceof EditorBlockEntity editor && editor.sessionId().equals(link.session())
                ? editor : null;
    }
    /** Position-agnostic existence check for a link's target, used to clean up stale links on
     *  headphones that are stored rather than worn (see {@link com.mervyn.groove.item.HeadphonesItem}).
     *  Unlike {@link #previewEditor}, this never clears a link just because it can't be reached
     *  or confirmed right now; it only reports a link as gone when the target chunk is loaded
     *  and demonstrably no longer holds a matching editor. */
    public static boolean linkedEditorExists(HeadphoneLinks.Link link, net.minecraft.server.MinecraftServer server) {
        var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, link.dimension()));
        if (level == null || !level.hasChunk(link.pos().getX() >> 4, link.pos().getZ() >> 4)) return true;
        return linkedEditor(level, link) != null;
    }
    static boolean allowsAsset(net.minecraft.server.level.ServerPlayer player, groove.engine.samples.AssetRef ref) {
        var editor = previewEditor(player);
        if (editor == null) return false;
        if (editor.project().preservesAsset(ref)) return true;
        if (editor.project().isUnreadable()) return false;
        return editor.session().draft().nodes().stream().anyMatch(node -> ref.equals(node.sample()));
    }
    /** Only a worn pair counts (see EDITOR-BLOCK-DESIGN.md: holding it only binds). */
    private static Optional<net.minecraft.world.item.ItemStack> wornHeadphones(net.minecraft.server.level.ServerPlayer player) {
        return TrinketsApi.getTrinketComponent(player)
                .map(component -> component.getEquipped(GrooveItems.HEADPHONES))
                .filter(equipped -> !equipped.isEmpty())
                .map(equipped -> equipped.getFirst().getB());
    }
    private HeadphoneServer() {}
}

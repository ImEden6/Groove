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
    public static void register() {
        HeadphonePackets.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> requests.clear());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> requests.remove(handler.player.getUUID()));
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
                if (!player.serverLevel().hasChunkAt(packet.pos()) || player.distanceToSqr(packet.pos().getCenter()) > 64)
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
            boolean available = false; String graph = ""; double bpm = 128; boolean playing = false; long revision = 0;
            var worn = wornHeadphones(player);
            if (worn.isPresent()) {
                var link = HeadphoneLinks.read(worn.get());
                if (link.isPresent() && link.get().dimension().equals(player.serverLevel().dimension().location())
                        && player.serverLevel().getBlockEntity(link.get().pos()) instanceof EditorBlockEntity editor
                        && editor.sessionId().equals(link.get().session())) {
                    var session = editor.session();
                    available = true; graph = GraphJson.encode(session.draft()); bpm = session.bpm();
                    playing = session.playing(); revision = session.revision();
                }
            }
            ServerPlayNetworking.send(player, new HeadphonePackets.Draft(packet.request(), available, graph, bpm, playing, revision));
        }));
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

package com.mervyn.groove.music;

import com.mervyn.groove.block.EditorBlockEntity;
import com.mervyn.groove.block.GrooveItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Burning reads the committed patch, which any listener can hear; loading changes the draft, so it needs edit access. */
public final class DiscServer {
    private static final Map<UUID, Long> requests = new HashMap<>();

    public static void register() {
        DiscPackets.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { requests.clear(); JukeboxSessions.clear(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> requests.remove(handler.player.getUUID()));
        ServerPlayNetworking.registerGlobalReceiver(DiscPackets.Use.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            boolean accepted = false;
            String message;
            try {
                long now = System.nanoTime();
                Long last = requests.get(player.getUUID());
                if (last != null && now - last < 250_000_000L) throw new IllegalArgumentException("Please wait before using a disc again");
                requests.put(player.getUUID(), now);
                if (player.isSpectator()) throw new IllegalArgumentException("Spectators can't use discs");
                if (!player.serverLevel().hasChunk(packet.pos().getX() >> 4, packet.pos().getZ() >> 4) || player.distanceToSqr(packet.pos().getCenter()) > 64)
                    throw new IllegalArgumentException("Editor is out of reach");
                if (!(player.serverLevel().getBlockEntity(packet.pos()) instanceof EditorBlockEntity editor))
                    throw new IllegalArgumentException("Not an editor block");
                if (!HeadphoneServer.isAvailable(editor.project())) throw new IllegalArgumentException("This editor's session can't be read");
                ItemStack held = player.getMainHandItem();
                if (held.is(GrooveItems.BLANK_DISC)) message = burn(player, held, editor, now);
                else if (held.is(GrooveItems.GROOVE_DISC)) message = load(player, held, editor, now);
                else throw new IllegalArgumentException("Hold a disc in your main hand");
                accepted = true;
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
            ServerPlayNetworking.send(player, new DiscPackets.State(packet.request(), accepted, message));
        }));
    }

    private static String burn(ServerPlayer player, ItemStack blank, EditorBlockEntity editor, long now) {
        var committed = editor.session().committed(now).current();
        ItemStack disc = new ItemStack(GrooveItems.GROOVE_DISC);
        DiscPatches.write(disc, committed.graph(), committed.bpm());
        // Creative players keep their blank, as with other items used up in survival
        if (!player.hasInfiniteMaterials()) blank.shrink(1);
        if (blank.isEmpty()) player.setItemInHand(InteractionHand.MAIN_HAND, disc);
        else if (!player.getInventory().add(disc)) player.drop(disc, false);
        player.inventoryMenu.broadcastChanges();
        return "Burned the committed patch (" + committed.graph().nodes().size() + " nodes, " + Math.round(committed.bpm()) + " BPM)";
    }

    private static String load(ServerPlayer player, ItemStack disc, EditorBlockEntity editor, long now) {
        if (!editor.canEdit(player.getUUID())) throw new IllegalArgumentException("You don't have edit access to this editor");
        var patch = DiscPatches.read(disc).orElseThrow(() -> new IllegalArgumentException("This disc is empty"));
        var graph = DiscPatches.graph(patch).orElseThrow(() -> new IllegalArgumentException("This disc can't be read"));
        var session = editor.session();
        session.edit(graph, patch.bpm(), session.playing(), now);
        editor.setChanged();
        return "Loaded the disc into the draft; Commit to publish it";
    }

    private DiscServer() {}
}

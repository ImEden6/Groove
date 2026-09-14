package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.block.EditorBlockEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-side headphone <-> editor links. Binding is unrestricted (see docs/EDITOR-BLOCK-DESIGN.md). */
public final class HeadphoneServer {
    private static final Map<UUID, HeadphoneLinks.Link> links = new HashMap<>();
    private static Path saveRoot;

    public static void register() {
        HeadphonePackets.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            saveRoot = server.getWorldPath(LevelResource.ROOT);
            links.clear();
            try { links.putAll(HeadphoneLinks.read(saveRoot)); }
            catch (Exception error) { GrooveMod.LOGGER.warn("Could not load headphone links; starting empty", error); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { links.clear(); saveRoot = null; });
        ServerPlayNetworking.registerGlobalReceiver(HeadphonePackets.Bind.TYPE, (packet, context) -> context.server().execute(() -> {
            var player = context.player();
            boolean accepted = false;
            BlockPos pos = BlockPos.ZERO;
            String message;
            try {
                if (!player.serverLevel().hasChunkAt(packet.pos()) || player.distanceToSqr(packet.pos().getCenter()) > 64)
                    throw new IllegalArgumentException("Editor is out of reach");
                if (!(player.serverLevel().getBlockEntity(packet.pos()) instanceof EditorBlockEntity editor))
                    throw new IllegalArgumentException("Not an editor block");
                links.put(player.getUUID(), new HeadphoneLinks.Link(packet.pos(), editor.sessionId()));
                save();
                accepted = true; pos = packet.pos();
                message = "Headphones linked to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
            ServerPlayNetworking.send(player, new HeadphonePackets.State(packet.request(), accepted, message, accepted, pos));
        }));
    }

    private static void save() {
        if (saveRoot == null) return;
        try { HeadphoneLinks.write(saveRoot, links); }
        catch (Exception error) { GrooveMod.LOGGER.error("Could not save headphone links", error); }
    }

    private HeadphoneServer() {}
}

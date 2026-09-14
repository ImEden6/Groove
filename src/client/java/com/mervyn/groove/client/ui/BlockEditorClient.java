package com.mervyn.groove.client.ui;

import com.mervyn.groove.music.EditorPackets;
import com.mervyn.groove.music.GraphJson;
import com.mervyn.groove.client.ui.theme.VanillaRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import java.util.UUID;

public final class BlockEditorClient {
    private static UUID opening;
    public static void register() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> opening = null);
        ClientPlayNetworking.registerGlobalReceiver(EditorPackets.State.TYPE, (packet, context) -> context.client().execute(() -> {
            if (packet.request().equals(opening)) {
                opening = null;
                if (!packet.accepted()) {
                    if (context.client().player != null) context.client().player.displayClientMessage(net.minecraft.network.chat.Component.literal(packet.message()), false);
                    return;
                }
                context.client().setScreen(new GrooveEditorScreen(GraphJson.decodeDraft(packet.graph()), new VanillaRenderer(), packet));
            } else if (context.client().screen instanceof GrooveEditorScreen screen) screen.blockState(packet);
        }));
    }
    public static void open(BlockPos pos) {
        opening = UUID.randomUUID();
        ClientPlayNetworking.send(new EditorPackets.Request(pos, new UUID(0, 0), opening, EditorPackets.OPEN, 0, "", 128, false));
    }
}

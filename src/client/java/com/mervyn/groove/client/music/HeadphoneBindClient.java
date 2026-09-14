package com.mervyn.groove.client.music;

import com.mervyn.groove.music.HeadphonePackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Sends the current headphone bind request and reports the server's ack in chat. */
public final class HeadphoneBindClient {
    private static UUID pending;
    private static long sent;
    public static void register() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pending = null);
        ClientPlayNetworking.registerGlobalReceiver(HeadphonePackets.State.TYPE, (packet, context) -> context.client().execute(() -> {
            if (!packet.request().equals(pending)) return;
            pending = null;
            if (context.client().player != null) context.client().player.displayClientMessage(Component.literal(packet.message()), false);
        }));
    }
    public static void bind(BlockPos pos) {
        long now = System.nanoTime();
        if (!ClientPlayNetworking.canSend(HeadphonePackets.Bind.TYPE) || now - sent < 250_000_000L
                || pending != null && now - sent < 5_000_000_000L) return;
        pending = UUID.randomUUID(); sent = now;
        ClientPlayNetworking.send(new HeadphonePackets.Bind(pos, pending));
    }
    private HeadphoneBindClient() {}
}

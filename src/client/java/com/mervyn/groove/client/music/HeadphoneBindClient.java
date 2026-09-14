package com.mervyn.groove.client.music;

import com.mervyn.groove.music.HeadphonePackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Sends the current headphone bind request and reports the server's ack in chat. */
public final class HeadphoneBindClient {
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(HeadphonePackets.State.TYPE, (packet, context) -> context.client().execute(() -> {
            if (context.client().player != null) context.client().player.displayClientMessage(Component.literal(packet.message()), false);
        }));
    }
    public static void bind(BlockPos pos) {
        ClientPlayNetworking.send(new HeadphonePackets.Bind(pos, UUID.randomUUID()));
    }
    private HeadphoneBindClient() {}
}

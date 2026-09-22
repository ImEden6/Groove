package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import java.util.UUID;

public final class DiscPackets {
    /** Right-clicking an editor with a blank disc burns it; with a burned disc, loads it into the draft. */
    public record Use(BlockPos pos, UUID request) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(GrooveMod.id("disc_use"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Use> CODEC = new StreamCodec<>() {
            public Use decode(RegistryFriendlyByteBuf b) { return new Use(b.readBlockPos(), b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, Use p) { b.writeBlockPos(p.pos); b.writeUUID(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(UUID request, boolean accepted, String message) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(GrooveMod.id("disc_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readUUID(), b.readBoolean(), b.readUtf(256)); }
            public void encode(RegistryFriendlyByteBuf b, State p) { b.writeUUID(p.request); b.writeBoolean(p.accepted); b.writeUtf(p.message, 256); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Use.TYPE, Use.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
    }
    private DiscPackets() {}
}

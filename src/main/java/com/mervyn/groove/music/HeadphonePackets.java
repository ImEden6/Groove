package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import java.util.UUID;

public final class HeadphonePackets {
    public record Bind(BlockPos pos, UUID request) implements CustomPacketPayload {
        public static final Type<Bind> TYPE = new Type<>(GrooveMod.id("headphone_bind"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Bind> CODEC = new StreamCodec<>() {
            public Bind decode(RegistryFriendlyByteBuf b) { return new Bind(b.readBlockPos(), b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, Bind p) { b.writeBlockPos(p.pos); b.writeUUID(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(UUID request, boolean accepted, String message, boolean bound, BlockPos pos) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(GrooveMod.id("headphone_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readUUID(), b.readBoolean(), b.readUtf(256), b.readBoolean(), b.readBlockPos()); }
            public void encode(RegistryFriendlyByteBuf b, State p) { b.writeUUID(p.request); b.writeBoolean(p.accepted); b.writeUtf(p.message, 256); b.writeBoolean(p.bound); b.writeBlockPos(p.pos); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Bind.TYPE, Bind.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
    }
    private HeadphonePackets() {}
}

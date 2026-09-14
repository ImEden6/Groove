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
    public record Preview(UUID request) implements CustomPacketPayload {
        public static final Type<Preview> TYPE = new Type<>(GrooveMod.id("headphone_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preview> CODEC = new StreamCodec<>() {
            public Preview decode(RegistryFriendlyByteBuf b) { return new Preview(b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, Preview p) { b.writeUUID(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** The linked editor's current draft, resolved server-side from the player's own worn headphones. */
    public record Draft(UUID request, boolean available, String graph, double bpm, boolean playing, long revision, UUID session, long at, double cycle) implements CustomPacketPayload {
        public static final Type<Draft> TYPE = new Type<>(GrooveMod.id("headphone_draft"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Draft> CODEC = new StreamCodec<>() {
            public Draft decode(RegistryFriendlyByteBuf b) { return new Draft(b.readUUID(), b.readBoolean(), b.readUtf(GraphJson.MAX_LENGTH), b.readDouble(), b.readBoolean(), b.readVarLong(), b.readUUID(), b.readLong(), b.readDouble()); }
            public void encode(RegistryFriendlyByteBuf b, Draft p) { b.writeUUID(p.request); b.writeBoolean(p.available); b.writeUtf(p.graph, GraphJson.MAX_LENGTH); b.writeDouble(p.bpm); b.writeBoolean(p.playing); b.writeVarLong(p.revision); b.writeUUID(p.session); b.writeLong(p.at); b.writeDouble(p.cycle); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Bind.TYPE, Bind.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.playC2S().register(Preview.TYPE, Preview.CODEC);
        PayloadTypeRegistry.playS2C().register(Draft.TYPE, Draft.CODEC);
    }
    private HeadphonePackets() {}
}

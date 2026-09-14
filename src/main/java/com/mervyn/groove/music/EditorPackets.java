package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EditorPackets {
    public static final int OPEN = 0, DRAFT = 1, COMMIT = 2;
    public record Request(BlockPos pos, UUID session, UUID request, int action, long revision,
                          String graph, double bpm, boolean playing) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(GrooveMod.id("editor_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readBlockPos(), b.readUUID(), b.readUUID(), b.readVarInt(), b.readVarLong(), b.readUtf(GraphJson.MAX_LENGTH), b.readDouble(), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, Request p) { b.writeBlockPos(p.pos); b.writeUUID(p.session); b.writeUUID(p.request); b.writeVarInt(p.action); b.writeVarLong(p.revision); b.writeUtf(p.graph, GraphJson.MAX_LENGTH); b.writeDouble(p.bpm); b.writeBoolean(p.playing); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(BlockPos pos, UUID session, UUID request, boolean accepted, String message,
                        long revision, String graph, double bpm, boolean playing) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(GrooveMod.id("editor_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readBlockPos(), b.readUUID(), b.readUUID(), b.readBoolean(), b.readUtf(512), b.readVarLong(), b.readUtf(GraphJson.MAX_LENGTH), b.readDouble(), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, State p) { b.writeBlockPos(p.pos); b.writeUUID(p.session); b.writeUUID(p.request); b.writeBoolean(p.accepted); b.writeUtf(p.message,512); b.writeVarLong(p.revision); b.writeUtf(p.graph,GraphJson.MAX_LENGTH); b.writeDouble(p.bpm); b.writeBoolean(p.playing); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record AllowlistRequest(BlockPos pos, UUID session, UUID request, String username, boolean allow) implements CustomPacketPayload {
        public static final Type<AllowlistRequest> TYPE = new Type<>(GrooveMod.id("editor_allowlist_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AllowlistRequest> CODEC = new StreamCodec<>() {
            public AllowlistRequest decode(RegistryFriendlyByteBuf b) { return new AllowlistRequest(b.readBlockPos(), b.readUUID(), b.readUUID(), b.readUtf(36), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, AllowlistRequest p) { b.writeBlockPos(p.pos); b.writeUUID(p.session); b.writeUUID(p.request); b.writeUtf(p.username, 36); b.writeBoolean(p.allow); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record AllowlistState(BlockPos pos, UUID session, UUID request, boolean accepted, String message,
                                 boolean owner, String ownerName, List<String> editors) implements CustomPacketPayload {
        public AllowlistState {
            editors = List.copyOf(editors);
            if (editors.size() > EditorProject.MAX_EDITORS) throw new IllegalArgumentException("Allowlist exceeds limit");
        }
        public static final Type<AllowlistState> TYPE = new Type<>(GrooveMod.id("editor_allowlist_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AllowlistState> CODEC = new StreamCodec<>() {
            public AllowlistState decode(RegistryFriendlyByteBuf b) {
                var pos = b.readBlockPos(); var session = b.readUUID(); var request = b.readUUID();
                boolean accepted = b.readBoolean(); String message = b.readUtf(512);
                boolean owner = b.readBoolean(); String ownerName = b.readUtf(36);
                int count = b.readVarInt();
                if (count < 0 || count > EditorProject.MAX_EDITORS) throw new IllegalArgumentException("Invalid allowlist size");
                var editors = new ArrayList<String>(count);
                for (int i = 0; i < count; i++) editors.add(b.readUtf(36));
                return new AllowlistState(pos, session, request, accepted, message, owner, ownerName, List.copyOf(editors));
            }
            public void encode(RegistryFriendlyByteBuf b, AllowlistState p) {
                b.writeBlockPos(p.pos); b.writeUUID(p.session); b.writeUUID(p.request);
                b.writeBoolean(p.accepted); b.writeUtf(p.message, 512);
                b.writeBoolean(p.owner); b.writeUtf(p.ownerName, 36);
                b.writeVarInt(p.editors.size());
                for (String name : p.editors) b.writeUtf(name, 36);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.playC2S().register(AllowlistRequest.TYPE, AllowlistRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(AllowlistState.TYPE, AllowlistState.CODEC);
    }
}

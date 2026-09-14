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

public final class SpeakerPackets {
    public static final int MAX_LISTED = 32;

    public record ListRequest(BlockPos editorPos, UUID session, UUID request) implements CustomPacketPayload {
        public static final Type<ListRequest> TYPE = new Type<>(GrooveMod.id("speaker_list_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ListRequest> CODEC = new StreamCodec<>() {
            public ListRequest decode(RegistryFriendlyByteBuf b) { return new ListRequest(b.readBlockPos(), b.readUUID(), b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, ListRequest p) { b.writeBlockPos(p.editorPos); b.writeUUID(p.session); b.writeUUID(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ListState(UUID request, boolean accepted, String message, List<BlockPos> speakers, List<Boolean> linked) implements CustomPacketPayload {
        public ListState {
            speakers = List.copyOf(speakers); linked = List.copyOf(linked);
            if (speakers.size() != linked.size() || speakers.size() > MAX_LISTED)
                throw new IllegalArgumentException("Invalid speaker list");
        }
        public static final Type<ListState> TYPE = new Type<>(GrooveMod.id("speaker_list_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ListState> CODEC = new StreamCodec<>() {
            public ListState decode(RegistryFriendlyByteBuf b) {
                UUID request = b.readUUID(); boolean accepted = b.readBoolean(); String message = b.readUtf(256);
                int count = b.readVarInt();
                if (count < 0 || count > MAX_LISTED) throw new IllegalArgumentException("Invalid speaker list size");
                var speakers = new ArrayList<BlockPos>(count);
                var linked = new ArrayList<Boolean>(count);
                for (int i = 0; i < count; i++) speakers.add(b.readBlockPos());
                for (int i = 0; i < count; i++) linked.add(b.readBoolean());
                return new ListState(request, accepted, message, speakers, linked);
            }
            public void encode(RegistryFriendlyByteBuf b, ListState p) {
                b.writeUUID(p.request); b.writeBoolean(p.accepted); b.writeUtf(p.message, 256);
                b.writeVarInt(p.speakers.size());
                for (BlockPos pos : p.speakers) b.writeBlockPos(pos);
                for (boolean flag : p.linked) b.writeBoolean(flag);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record BindRequest(BlockPos editorPos, UUID session, BlockPos speakerPos, boolean link, UUID request) implements CustomPacketPayload {
        public static final Type<BindRequest> TYPE = new Type<>(GrooveMod.id("speaker_bind_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BindRequest> CODEC = new StreamCodec<>() {
            public BindRequest decode(RegistryFriendlyByteBuf b) { return new BindRequest(b.readBlockPos(), b.readUUID(), b.readBlockPos(), b.readBoolean(), b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, BindRequest p) { b.writeBlockPos(p.editorPos); b.writeUUID(p.session); b.writeBlockPos(p.speakerPos); b.writeBoolean(p.link); b.writeUUID(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record BindState(UUID request, boolean accepted, String message) implements CustomPacketPayload {
        public static final Type<BindState> TYPE = new Type<>(GrooveMod.id("speaker_bind_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BindState> CODEC = new StreamCodec<>() {
            public BindState decode(RegistryFriendlyByteBuf b) { return new BindState(b.readUUID(), b.readBoolean(), b.readUtf(256)); }
            public void encode(RegistryFriendlyByteBuf b, BindState p) { b.writeUUID(p.request); b.writeBoolean(p.accepted); b.writeUtf(p.message, 256); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Public/positional, like the retired global broadcast: any nearby player may ask what a speaker plays. */
    public record CommittedRequest(BlockPos pos, UUID request) implements CustomPacketPayload {
        public static final Type<CommittedRequest> TYPE = new Type<>(GrooveMod.id("speaker_committed_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CommittedRequest> CODEC = new StreamCodec<>() {
            public CommittedRequest decode(RegistryFriendlyByteBuf b) { return new CommittedRequest(b.readBlockPos(), b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, CommittedRequest p) { b.writeBlockPos(p.pos); b.writeUUID(p.request); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record CommittedState(BlockPos pos, UUID request, boolean available, MusicPackets.Snapshot timeline) implements CustomPacketPayload {
        public CommittedState {
            if (available != (timeline != null)) throw new IllegalArgumentException("Invalid speaker timeline availability");
        }
        /** Epoch identifies the editor session; revisions alone cannot identify a relink. */
        public boolean samePublication(CommittedState other) {
            return other != null && available == other.available && java.util.Objects.equals(timeline, other.timeline);
        }
        public static final Type<CommittedState> TYPE = new Type<>(GrooveMod.id("speaker_committed_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CommittedState> CODEC = new StreamCodec<>() {
            public CommittedState decode(RegistryFriendlyByteBuf b) {
                var pos = b.readBlockPos(); var request = b.readUUID(); boolean available = b.readBoolean();
                return new CommittedState(pos, request, available, available ? MusicPackets.Snapshot.CODEC.decode(b) : null);
            }
            public void encode(RegistryFriendlyByteBuf b, CommittedState p) {
                b.writeBlockPos(p.pos); b.writeUUID(p.request); b.writeBoolean(p.available);
                if (p.available) MusicPackets.Snapshot.CODEC.encode(b, p.timeline);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(ListRequest.TYPE, ListRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(ListState.TYPE, ListState.CODEC);
        PayloadTypeRegistry.playC2S().register(BindRequest.TYPE, BindRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(BindState.TYPE, BindState.CODEC);
        PayloadTypeRegistry.playC2S().register(CommittedRequest.TYPE, CommittedRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(CommittedState.TYPE, CommittedState.CODEC);
    }
    private SpeakerPackets() {}
}

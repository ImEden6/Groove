package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import groove.engine.SessionState;
import groove.engine.SessionTimeline;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;
import groove.engine.samples.AssetRef;
import groove.engine.samples.SampleData;
import groove.engine.samples.SampleTransfer;

public final class MusicPackets {
    public record WireState(long revision, long at, double cycle, double bpm, boolean playing, String graph) {
        WireState(SessionState state) {
            this(state.revision(), state.effectiveNanos(), state.anchorCycle(), state.bpm(), state.playing(), GraphJson.encode(state.graph()));
        }
        SessionState decode() { return new SessionState(revision, at, cycle, bpm, playing, GraphJson.decode(graph)); }
    }
    public record Snapshot(UUID epoch, WireState current, WireState pending) implements CustomPacketPayload {
        public Snapshot(UUID epoch, SessionTimeline.Snapshot snapshot) {
            this(epoch, new WireState(snapshot.current()), snapshot.pending() == null ? null : new WireState(snapshot.pending()));
        }
        public long revision() { return pending == null ? current.revision() : pending.revision(); }
        /** Compiler-worker only; the wire codec deliberately does not parse graph JSON. */
        public SessionTimeline.Snapshot snapshot() {
            return new SessionTimeline.Snapshot(current.decode(), pending == null ? null : pending.decode());
        }
        public static final Type<Snapshot> TYPE = new Type<>(GrooveMod.id("music_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf buf) {
                UUID epoch = buf.readUUID();
                WireState current = readState(buf);
                WireState pending = buf.readBoolean() ? readState(buf) : null;
                return new Snapshot(epoch, current, pending);
            }
            public void encode(RegistryFriendlyByteBuf buf, Snapshot packet) {
                buf.writeUUID(packet.epoch);
                writeState(buf, packet.current());
                buf.writeBoolean(packet.pending() != null);
                if (packet.pending() != null) writeState(buf, packet.pending());
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Ping(long sent) implements CustomPacketPayload {
        public static final Type<Ping> TYPE = new Type<>(GrooveMod.id("music_ping"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ping> CODEC = new StreamCodec<>() {
            public Ping decode(RegistryFriendlyByteBuf buf) { return new Ping(buf.readLong()); }
            public void encode(RegistryFriendlyByteBuf buf, Ping packet) { buf.writeLong(packet.sent); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Pong(long sent, long serverNanos) implements CustomPacketPayload {
        public static final Type<Pong> TYPE = new Type<>(GrooveMod.id("music_pong"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pong> CODEC = new StreamCodec<>() {
            public Pong decode(RegistryFriendlyByteBuf buf) { return new Pong(buf.readLong(), buf.readLong()); }
            public void encode(RegistryFriendlyByteBuf buf, Pong packet) { buf.writeLong(packet.sent); buf.writeLong(packet.serverNanos); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Submit.TYPE, Submit.CODEC);
        PayloadTypeRegistry.playS2C().register(SubmitResult.TYPE, SubmitResult.CODEC);
        PayloadTypeRegistry.playS2C().register(Snapshot.TYPE, Snapshot.CODEC);
        PayloadTypeRegistry.playC2S().register(Ping.TYPE, Ping.CODEC);
        PayloadTypeRegistry.playS2C().register(Pong.TYPE, Pong.CODEC);
        PayloadTypeRegistry.playC2S().register(AssetRequest.TYPE, AssetRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(AssetChunk.TYPE, AssetChunk.CODEC);
    }
    /** Keep JSON bounded on the wire; expensive validation happens after permission/rate checks. */
    public record Submit(UUID epoch, UUID request, long revision, String graph, double bpm, boolean playing) implements CustomPacketPayload {
        public static final Type<Submit> TYPE = new Type<>(GrooveMod.id("patch_submit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Submit> CODEC = new StreamCodec<>() {
            public Submit decode(RegistryFriendlyByteBuf b) { return new Submit(b.readUUID(), b.readUUID(), b.readVarLong(), b.readUtf(GraphJson.MAX_LENGTH), b.readDouble(), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, Submit p) { b.writeUUID(p.epoch); b.writeUUID(p.request); b.writeVarLong(p.revision); b.writeUtf(p.graph, GraphJson.MAX_LENGTH); b.writeDouble(p.bpm); b.writeBoolean(p.playing); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SubmitResult(UUID request, boolean accepted, String message) implements CustomPacketPayload {
        public static final Type<SubmitResult> TYPE = new Type<>(GrooveMod.id("patch_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SubmitResult> CODEC = new StreamCodec<>() {
            public SubmitResult decode(RegistryFriendlyByteBuf b) { return new SubmitResult(b.readUUID(), b.readBoolean(), b.readUtf(512)); }
            public void encode(RegistryFriendlyByteBuf b, SubmitResult p) { b.writeUUID(p.request); b.writeBoolean(p.accepted); b.writeUtf(p.message, 512); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record AssetRequest(AssetRef ref, int offset) implements CustomPacketPayload {
        public AssetRequest { if (offset < 0 || offset >= SampleData.MAX_BYTES) throw new IllegalArgumentException("Invalid asset offset"); }
        public static final Type<AssetRequest> TYPE = new Type<>(GrooveMod.id("asset_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AssetRequest> CODEC = new StreamCodec<>() {
            public AssetRequest decode(RegistryFriendlyByteBuf b) { return new AssetRequest(readRef(b), b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, AssetRequest p) { writeRef(b, p.ref); b.writeVarInt(p.offset); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record AssetChunk(AssetRef ref, int offset, int total, byte[] data) implements CustomPacketPayload {
        public AssetChunk {
            if (total < 0 || total > SampleData.MAX_BYTES || offset < 0 || offset > total
                    || data.length > SampleTransfer.CHUNK_BYTES || (long) offset + data.length > total)
                throw new IllegalArgumentException("Invalid asset chunk bounds");
        }
        public static final Type<AssetChunk> TYPE = new Type<>(GrooveMod.id("asset_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AssetChunk> CODEC = new StreamCodec<>() {
            public AssetChunk decode(RegistryFriendlyByteBuf b) { return new AssetChunk(readRef(b), b.readVarInt(), b.readVarInt(), b.readByteArray(SampleTransfer.CHUNK_BYTES)); }
            public void encode(RegistryFriendlyByteBuf b, AssetChunk p) {
                writeRef(b, p.ref); b.writeVarInt(p.offset); b.writeVarInt(p.total); b.writeByteArray(p.data);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static AssetRef readRef(RegistryFriendlyByteBuf b) { return new AssetRef(b.readUtf(160), b.readUtf(64)); }
    private static void writeRef(RegistryFriendlyByteBuf b, AssetRef ref) { b.writeUtf(ref.assetId(), 160); b.writeUtf(ref.sha256(), 64); }
    private static WireState readState(RegistryFriendlyByteBuf buf) {
        long revision = buf.readVarLong(), at = buf.readLong();
        double cycle = buf.readDouble(), bpm = buf.readDouble();
        boolean playing = buf.readBoolean();
        return new WireState(revision, at, cycle, bpm, playing, buf.readUtf(GraphJson.MAX_LENGTH));
    }
    private static void writeState(RegistryFriendlyByteBuf buf, WireState state) {
        buf.writeVarLong(state.revision()); buf.writeLong(state.at());
        buf.writeDouble(state.cycle()); buf.writeDouble(state.bpm()); buf.writeBoolean(state.playing());
        buf.writeUtf(state.graph(), GraphJson.MAX_LENGTH);
    }
}

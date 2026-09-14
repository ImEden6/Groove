package com.mervyn.groove.music;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.List;
import java.util.UUID;

final class SpeakerLinkTests {
    static void run() {
        var tag = new CompoundTag();
        tag.putString("other:data", "keep");
        check(SpeakerLinks.read(tag).isEmpty(), "New speaker is unlinked");
        var pos = new BlockPos.MutableBlockPos(4, 5, 6);
        var session = UUID.randomUUID();
        var link = new SpeakerLinks.Link(pos, session);
        pos.set(7, 8, 9);
        check(link.pos().equals(new BlockPos(4, 5, 6)), "Link owns an immutable position");
        SpeakerLinks.write(tag, link);
        check(SpeakerLinks.read(tag).orElseThrow().equals(link), "Tag stores position and session");
        check(tag.getString("other:data").equals("keep"), "Writing a link preserves unrelated block entity data");
        var other = new SpeakerLinks.Link(new BlockPos(1, 2, 3), UUID.randomUUID());
        SpeakerLinks.write(tag, other);
        check(SpeakerLinks.read(tag).orElseThrow().equals(other), "Rebinding replaces the link exclusively");
        SpeakerLinks.clear(tag);
        check(SpeakerLinks.read(tag).isEmpty(), "Clearing removes the link");
        check(tag.getString("other:data").equals("keep"), "Clearing preserves unrelated block entity data");

        var editorPos = new BlockPos(10, 64, 10);
        var listRequest = new SpeakerPackets.ListRequest(editorPos, session, UUID.randomUUID());
        var listState = new SpeakerPackets.ListState(listRequest.request(), true, "",
                List.of(new BlockPos(11, 64, 10), new BlockPos(12, 64, 10)), List.of(true, false));
        var bindRequest = new SpeakerPackets.BindRequest(editorPos, session, new BlockPos(11, 64, 10), true, UUID.randomUUID());
        var bindState = new SpeakerPackets.BindState(bindRequest.request(), true, "Speaker linked");
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            SpeakerPackets.ListRequest.CODEC.encode(wire, listRequest);
            check(SpeakerPackets.ListRequest.CODEC.decode(wire).equals(listRequest), "Speaker list request round trip");
            SpeakerPackets.ListState.CODEC.encode(wire, listState);
            check(SpeakerPackets.ListState.CODEC.decode(wire).equals(listState), "Speaker list state round trip");
            var throttledListState = new SpeakerPackets.ListState(listRequest.request(), false, "Please wait before refreshing", List.of(), List.of());
            SpeakerPackets.ListState.CODEC.encode(wire, throttledListState);
            check(SpeakerPackets.ListState.CODEC.decode(wire).equals(throttledListState), "Throttled speaker list state round trip");
            SpeakerPackets.BindRequest.CODEC.encode(wire, bindRequest);
            check(SpeakerPackets.BindRequest.CODEC.decode(wire).equals(bindRequest), "Speaker bind request round trip");
            SpeakerPackets.BindState.CODEC.encode(wire, bindState);
            check(SpeakerPackets.BindState.CODEC.decode(wire).equals(bindState), "Speaker bind state round trip");
            var speakerPos = new BlockPos(11, 64, 10);
            var committedRequest = new SpeakerPackets.CommittedRequest(speakerPos, UUID.randomUUID());
            SpeakerPackets.CommittedRequest.CODEC.encode(wire, committedRequest);
            check(SpeakerPackets.CommittedRequest.CODEC.decode(wire).equals(committedRequest), "Speaker committed request round trip");
            var committedState = new SpeakerPackets.CommittedState(speakerPos, committedRequest.request(), true,
                    GraphJson.encode(groove.engine.Graph.demo()), 140, true, 3, 12345, 2.5);
            SpeakerPackets.CommittedState.CODEC.encode(wire, committedState);
            check(SpeakerPackets.CommittedState.CODEC.decode(wire).equals(committedState), "Speaker committed state round trip");
            var unavailable = new SpeakerPackets.CommittedState(speakerPos, UUID.randomUUID(), false, "", 128, false, 0, 0, 0);
            SpeakerPackets.CommittedState.CODEC.encode(wire, unavailable);
            check(SpeakerPackets.CommittedState.CODEC.decode(wire).equals(unavailable), "Unavailable committed state round trip");
        } finally { wire.release(); }
        reject(() -> new SpeakerPackets.ListState(UUID.randomUUID(), true, "", List.of(new BlockPos(0, 0, 0)), List.of()),
                "Mismatched speaker/linked list lengths are rejected");
        reject(() -> new SpeakerPackets.ListState(UUID.randomUUID(), true, "",
                java.util.Collections.nCopies(SpeakerPackets.MAX_LISTED + 1, BlockPos.ZERO),
                java.util.Collections.nCopies(SpeakerPackets.MAX_LISTED + 1, false)), "Oversized speaker list is rejected");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
}

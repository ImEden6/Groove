package com.mervyn.groove.music;

import groove.engine.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;
import static com.mervyn.groove.music.TestSupport.*;

final class EditorSessionTests {
    static void run() {
        var clocked = new EditorSession(0);
        clocked.edit(Graph.demo(), 120, true, 0);
        clocked.edit(Graph.demo(), 240, true, 2_000_000_000L);
        check(clocked.preview().anchorCycle() == 1 && clocked.preview().cycleAt(3_000_000_000L) == 2, "Draft tempo changes preserve the server musical phase");
        clocked.edit(Graph.demo(), 240, false, 3_000_000_000L);
        check(clocked.preview().cycleAt(9_000_000_000L) == 2, "Stopped draft holds its phase");
        var lfo = new Graph(3, List.of(new Graph.Node("lfo", NodeType.LFO, Map.of())), List.of());
        clocked.edit(lfo, 120, true, 10_000_000_000L);
        clocked.edit(lfo, 130, true, 11_000_000_000L);
        check(clocked.preview().graph().nodes().getFirst().birthNanos() == 10_000_000_000L, "Unchanged LFO retains birth across edits");
        var a = new EditorSession(0);
        var b = new EditorSession(0);
        var changed = new Graph(3, Graph.demo().nodes(), Graph.demo().edges());
        a.edit(changed, 150, true);
        check(a.draft().equals(changed) && b.bpm() == 128, "Blocks have independent drafts");
        check(a.committed(0).current().bpm() == 128 && a.committed(0).pending() == null, "Draft does not publish");
        a.edit(changed, 160, true);
        check(a.bpm() == 160 && a.revision() == 2, "Draft writes use last write wins");
        reject(() -> a.commit(1, 0), "Unseen draft cannot be committed");
        a.commit(2, 0);
        check(a.committed(0).pending().bpm() == 160, "Commit queues the draft");
        a.edit(changed, 170, false);
        check(a.committed(0).pending().bpm() == 160, "Later draft preserves queued commit");
        reject(() -> a.commit(3, 0), "Pending commit rejects another commit");
        check(a.committed(2_000_000_000L).current().bpm() == 160, "Commit takes effect on timeline");
        var incomplete = new Graph(3, List.of(), List.of());
        a.edit(GraphJson.decodeDraft(GraphJson.encode(incomplete)), 120, false);
        reject(() -> a.commit(a.revision(), 2_000_000_000L), "Incomplete draft cannot publish");
        check(a.draft().equals(incomplete) && a.committed(2_000_000_000L).current().bpm() == 160, "Rejected commit preserves both states");
        reject(() -> a.edit(changed, Double.NaN, false), "Invalid tempo rejected");
        check(a.draft().equals(incomplete), "Rejected draft leaves state intact");

        var pos = new BlockPos(1, 2, 3);
        var owner = UUID.randomUUID();
        var collaborator = UUID.randomUUID();
        var block = new EditorProject();
        block.setOwner(owner);
        block.allowEditor(owner, collaborator, true);
        check(block.canEdit(collaborator) && !block.canEdit(UUID.randomUUID()), "Allowlist protects edits");
        reject(() -> block.allowEditor(collaborator, UUID.randomUUID(), true), "Only the owner manages the allowlist");
        reject(() -> block.allowEditor(owner, owner, true), "Owner cannot be added to their own allowlist");
        block.session().edit(changed, 140, true);
        block.session().commit(block.session().revision(), System.nanoTime());
        block.session().edit(incomplete, 180, false);
        var saved = new net.minecraft.nbt.CompoundTag();
        block.save(saved);
        var restored = new EditorProject();
        restored.load(saved);
        check(restored.sessionId().equals(block.sessionId()) && restored.canEdit(owner) && restored.canEdit(collaborator), "Identity and ACL survive reload");
        check(restored.session().draft().equals(incomplete) && restored.session().bpm() == 180, "Incomplete draft survives reload");
        check(restored.session().committed(System.nanoTime()).current().bpm() == 140, "Published patch saved independently, including queued commit");
        check(restored.session().revision() == block.session().revision(), "Draft revision survives reload");
        check(restored.session().committed(System.nanoTime()).current().playing(), "Published transport survives reload");
        check(!new EditorProject().sessionId().equals(block.sessionId()), "Replacement block has a new identity");
        for (int i = restored.editors().size(); i < EditorProject.MAX_EDITORS; i++) restored.allowEditor(owner, UUID.randomUUID(), true);
        restored.allowEditor(owner, collaborator, true);
        reject(() -> restored.allowEditor(owner, UUID.randomUUID(), true), "Allowlist is bounded without rejecting an existing editor");
        restored.allowEditor(owner, collaborator, false);
        check(!restored.canEdit(collaborator), "Removal revokes edit access even when list was full");
        restored.allowEditor(owner, UUID.randomUUID(), true);
        var request = new EditorPackets.Request(pos, block.sessionId(), UUID.randomUUID(), EditorPackets.COMMIT, 2, "", 140, true);
        var response = new EditorPackets.State(pos, block.sessionId(), request.request(), true, "Saved", 2, GraphJson.encode(incomplete), 180, false,
                List.of("Steve", "Alex"));
        var closeRequest = new EditorPackets.Request(pos, block.sessionId(), UUID.randomUUID(), EditorPackets.CLOSE, 0, "", 0, false);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            EditorPackets.Request.CODEC.encode(wire, request);
            check(EditorPackets.Request.CODEC.decode(wire).equals(request), "Editor request round trip");
            EditorPackets.Request.CODEC.encode(wire, closeRequest);
            check(EditorPackets.Request.CODEC.decode(wire).equals(closeRequest), "Editor close request round trip");
            EditorPackets.State.CODEC.encode(wire, response);
            check(EditorPackets.State.CODEC.decode(wire).equals(response), "Editor state round trip");
            var viewers = new ArrayList<String>(List.of("Steve"));
            var ownsViewers = new EditorPackets.State(pos, block.sessionId(), UUID.randomUUID(), true, "", 2, GraphJson.encode(incomplete), 180, false, viewers);
            viewers.clear();
            check(ownsViewers.viewers().size() == 1, "Editor state packet owns its viewer list");
            reject(() -> new EditorPackets.State(pos, block.sessionId(), UUID.randomUUID(), true, "", 2, GraphJson.encode(incomplete), 180, false,
                    Collections.nCopies(EditorProject.MAX_EDITORS + 1, "Steve")), "Oversized viewer list rejected");
            var allowlistRequest = new EditorPackets.AllowlistRequest(pos, block.sessionId(), UUID.randomUUID(), "Steve", true);
            var allowlistState = new EditorPackets.AllowlistState(pos, block.sessionId(), allowlistRequest.request(), true, "Added Steve",
                    true, "Alex", List.of("Herobrine", "Steve"));
            EditorPackets.AllowlistRequest.CODEC.encode(wire, allowlistRequest);
            check(EditorPackets.AllowlistRequest.CODEC.decode(wire).equals(allowlistRequest), "Allowlist request round trip");
            EditorPackets.AllowlistState.CODEC.encode(wire, allowlistState);
            check(EditorPackets.AllowlistState.CODEC.decode(wire).equals(allowlistState), "Allowlist state round trip");
            var removeUnknown = new EditorPackets.AllowlistRequest(pos, block.sessionId(), UUID.randomUUID(), collaborator.toString(), false);
            EditorPackets.AllowlistRequest.CODEC.encode(wire, removeUnknown);
            check(EditorPackets.AllowlistRequest.CODEC.decode(wire).equals(removeUnknown), "Unknown-name editor can be removed by full UUID");
            var names = new ArrayList<String>(List.of(collaborator.toString()));
            var unknownNames = new EditorPackets.AllowlistState(pos, block.sessionId(), UUID.randomUUID(), true, "", true, owner.toString(), names);
            names.clear();
            check(unknownNames.editors().size() == 1, "Allowlist packet owns its entries");
            EditorPackets.AllowlistState.CODEC.encode(wire, unknownNames);
            check(EditorPackets.AllowlistState.CODEC.decode(wire).equals(unknownNames), "Unknown names survive full UUID packet round trip");
            reject(() -> new EditorPackets.AllowlistState(pos, block.sessionId(), UUID.randomUUID(), true, "", true, "Alex",
                    Collections.nCopies(EditorProject.MAX_EDITORS + 1, "Steve")), "Oversized allowlist packet rejected");
            for (int count : new int[] {-1, EditorProject.MAX_EDITORS + 1}) {
                wire.clear();
                wire.writeBlockPos(pos); wire.writeUUID(block.sessionId()); wire.writeUUID(UUID.randomUUID());
                wire.writeBoolean(true); wire.writeUtf(""); wire.writeBoolean(true); wire.writeUtf("Alex"); wire.writeVarInt(count);
                reject(() -> EditorPackets.AllowlistState.CODEC.decode(wire), "Invalid wire count rejected before reading entries");
            }
        } finally { wire.release(); }
    }
}

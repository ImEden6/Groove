package com.mervyn.groove.music;

import groove.engine.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;

final class EditorSessionTests {
    static void run() {
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
        var request = new EditorPackets.Request(pos, block.sessionId(), UUID.randomUUID(), EditorPackets.COMMIT, 2, "", 140, true);
        var response = new EditorPackets.State(pos, block.sessionId(), request.request(), true, "Saved", 2, GraphJson.encode(incomplete), 180, false);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            EditorPackets.Request.CODEC.encode(wire, request);
            check(EditorPackets.Request.CODEC.decode(wire).equals(request), "Editor request round trip");
            EditorPackets.State.CODEC.encode(wire, response);
            check(EditorPackets.State.CODEC.decode(wire).equals(response), "Editor state round trip");
        } finally { wire.release(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
}

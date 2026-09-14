package com.mervyn.groove.music;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Persistent block identity, access list, and independent draft/published state. */
public final class EditorProject {
    private UUID sessionId = UUID.randomUUID();
    private UUID owner;
    private final java.util.Set<UUID> editors = new java.util.HashSet<>();
    private EditorSession session = new EditorSession(System.nanoTime());
    public UUID sessionId() { return sessionId; }
    public EditorSession session() { return session; }
    public UUID owner() { return owner; }
    public java.util.Set<UUID> editors() { return java.util.Set.copyOf(editors); }
    public boolean hasOwner() { return owner != null; }
    public void setOwner(UUID player) { if (owner == null) { owner = player; } }
    public boolean canEdit(UUID player) { return owner != null && (owner.equals(player) || editors.contains(player)); }
    /** Only the owner manages the allowlist; other allowlisted editors cannot. */
    public void allowEditor(UUID actor, UUID player, boolean allowed) {
        if (owner == null || !owner.equals(actor)) throw new IllegalArgumentException("Only the owner manages the allowlist");
        if (player.equals(owner)) throw new IllegalArgumentException("The owner already has full access");
        if (allowed) editors.add(player); else editors.remove(player);
    }
    public void save(CompoundTag tag) {
        tag.putUUID("Session", sessionId);
        if (owner != null) tag.putUUID("Owner", owner);
        var allowlist = new net.minecraft.nbt.ListTag();
        for (UUID player : editors) allowlist.add(net.minecraft.nbt.StringTag.valueOf(player.toString()));
        tag.put("Editors", allowlist);
        tag.putBoolean("DraftPlaying", session.playing());
        tag.putLong("DraftRevision", session.revision());
        tag.putString("Draft", GraphJson.encode(session.draft()));
        tag.putDouble("DraftBpm", session.bpm());
        var snapshot = session.committed(System.nanoTime());
        var published = snapshot.pending() == null ? snapshot.current() : snapshot.pending();
        tag.putString("Published", GraphJson.encode(published.graph()));
        tag.putDouble("PublishedBpm", published.bpm());
        tag.putBoolean("PublishedPlaying", published.playing());
    }
    public void load(CompoundTag tag) {
        if (tag.hasUUID("Session")) sessionId = tag.getUUID("Session");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        editors.clear();
        var allowlist = tag.getList("Editors", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < allowlist.size(); i++) editors.add(UUID.fromString(allowlist.getString(i)));
        if (tag.contains("Draft")) {
            // decodeDraft: SessionTimeline below compiles the published graph anyway.
            session = new EditorSession(GraphJson.decodeDraft(tag.getString("Draft")), tag.getDouble("DraftBpm"),
                    tag.getBoolean("DraftPlaying"), tag.getLong("DraftRevision"),
                    GraphJson.decodeDraft(tag.getString("Published")), tag.getDouble("PublishedBpm"),
                    tag.getBoolean("PublishedPlaying"), System.nanoTime());
        }
    }
}

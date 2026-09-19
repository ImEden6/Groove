package com.mervyn.groove.music;

import groove.engine.Graph;
import groove.engine.samples.AssetRef;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

/** Persistent block identity, access list, and independent draft/published state. */
public final class EditorProject {
    public static final int MAX_EDITORS = 64;
    private UUID sessionId = UUID.randomUUID();
    private UUID owner;
    private final Set<UUID> editors = new HashSet<>();
    private EditorSession session = new EditorSession(System.nanoTime());

    private String rawDraft;
    private double draftBpm = 128;
    private boolean draftPlaying;
    private long draftRevision;
    private boolean draftUnreadable;
    private String draftError;

    private String rawPublished;
    private double publishedBpm = 128;
    private boolean publishedPlaying;
    private boolean publishedUnreadable;
    private String publishedError;

    private final Set<AssetRef> preservedAssets = new HashSet<>();
    private final Set<String> freeRunDelays = new java.util.LinkedHashSet<>();

    public UUID sessionId() { return sessionId; }
    public EditorSession session() { return session; }
    public UUID owner() { return owner; }
    public Set<UUID> editors() { return Set.copyOf(editors); }
    public boolean hasOwner() { return owner != null; }
    public void setOwner(UUID player) { if (owner == null) { owner = player; } }
    public boolean canEdit(UUID player) { return owner != null && (owner.equals(player) || editors.contains(player)); }

    public boolean isUnreadable() { return draftUnreadable || publishedUnreadable; }
    public boolean isDraftUnreadable() { return draftUnreadable; }
    public boolean isPublishedUnreadable() { return publishedUnreadable; }
    public String draftError() { return draftError; }
    public String publishedError() { return publishedError; }
    public String rawDraft() { return rawDraft; }
    public String rawPublished() { return rawPublished; }
    public boolean preservesAsset(AssetRef ref) { return preservedAssets.contains(ref); }
    public Set<AssetRef> preservedAssets() { return Set.copyOf(preservedAssets); }
    /** Delays the last load marked free-running to keep an older patch's feedback. */
    public Set<String> freeRunDelays() { return Set.copyOf(freeRunDelays); }

    /** Only the owner manages the allowlist; other allowlisted editors cannot. */
    public void allowEditor(UUID actor, UUID player, boolean allowed) {
        if (owner == null || !owner.equals(actor)) throw new IllegalArgumentException("Only the owner manages the allowlist");
        if (player.equals(owner)) throw new IllegalArgumentException("The owner already has full access");
        if (allowed && !editors.contains(player) && editors.size() >= MAX_EDITORS)
            throw new IllegalArgumentException("Allowlist is full (64 editors)");
        if (allowed) editors.add(player); else editors.remove(player);
    }

    public void save(CompoundTag tag) {
        tag.putUUID("Session", sessionId);
        if (owner != null) tag.putUUID("Owner", owner);
        var allowlist = new net.minecraft.nbt.ListTag();
        for (UUID player : editors) allowlist.add(net.minecraft.nbt.StringTag.valueOf(player.toString()));
        tag.put("Editors", allowlist);

        if (draftUnreadable && rawDraft != null) {
            tag.putString("Draft", rawDraft);
            tag.putDouble("DraftBpm", draftBpm);
            tag.putBoolean("DraftPlaying", draftPlaying);
            tag.putLong("DraftRevision", draftRevision);
        } else {
            tag.putBoolean("DraftPlaying", session.playing());
            tag.putLong("DraftRevision", session.revision());
            tag.putString("Draft", GraphJson.encode(session.draft()));
            tag.putDouble("DraftBpm", session.bpm());
        }

        if (publishedUnreadable && rawPublished != null) {
            tag.putString("Published", rawPublished);
            tag.putDouble("PublishedBpm", publishedBpm);
            tag.putBoolean("PublishedPlaying", publishedPlaying);
        } else {
            var snapshot = session.committed(System.nanoTime());
            var published = snapshot.pending() == null ? snapshot.current() : snapshot.pending();
            tag.putString("Published", GraphJson.encode(published.graph()));
            tag.putDouble("PublishedBpm", published.bpm());
            tag.putBoolean("PublishedPlaying", published.playing());
        }
    }

    public void load(CompoundTag tag) {
        if (tag.hasUUID("Session")) sessionId = tag.getUUID("Session");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        editors.clear();
        var allowlist = tag.getList("Editors", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < allowlist.size(); i++) editors.add(UUID.fromString(allowlist.getString(i)));

        preservedAssets.clear();
        freeRunDelays.clear();
        draftUnreadable = false;
        draftError = null;
        publishedUnreadable = false;
        publishedError = null;
        rawDraft = null;
        rawPublished = null;

        Graph draftGraph = null;
        Graph publishedGraph = null;

        if (tag.contains("Draft")) {
            rawDraft = tag.getString("Draft");
            draftBpm = tag.contains("DraftBpm") ? tag.getDouble("DraftBpm") : 128.0;
            if (draftBpm < 30 || draftBpm > 300 || !Double.isFinite(draftBpm)) draftBpm = 128.0;
            draftPlaying = tag.getBoolean("DraftPlaying");
            draftRevision = tag.getLong("DraftRevision");
            try {
                var migrated = GraphJson.decodeSavedDraft(rawDraft);
                draftGraph = migrated.graph();
                freeRunDelays.addAll(migrated.freeRunDelays());
            } catch (RuntimeException error) {
                draftUnreadable = true;
                draftError = error.getMessage();
                draftGraph = null;
                extractSampleRefs(rawDraft, preservedAssets);
            }
        }
        if (tag.contains("Published")) {
            rawPublished = tag.getString("Published");
            publishedBpm = tag.contains("PublishedBpm") ? tag.getDouble("PublishedBpm") : 128.0;
            if (publishedBpm < 30 || publishedBpm > 300 || !Double.isFinite(publishedBpm)) publishedBpm = 128.0;
            publishedPlaying = tag.getBoolean("PublishedPlaying");
            try {
                var migrated = GraphJson.decodeSaved(rawPublished);
                publishedGraph = migrated.graph();
                freeRunDelays.addAll(migrated.freeRunDelays());
            } catch (RuntimeException error) {
                publishedUnreadable = true;
                publishedError = error.getMessage();
                publishedGraph = null;
                extractSampleRefs(rawPublished, preservedAssets);
            }
        }
        if (tag.contains("Draft") || tag.contains("Published")) {
            Graph dGraph = (!draftUnreadable && draftGraph != null) ? draftGraph
                    : ((!publishedUnreadable && publishedGraph != null) ? publishedGraph : Graph.demo());
            Graph pGraph = (!publishedUnreadable && publishedGraph != null) ? publishedGraph
                    : ((!draftUnreadable && draftGraph != null) ? draftGraph : Graph.demo());
            try {
                session = new EditorSession(dGraph, draftBpm, draftPlaying, draftRevision,
                        pGraph, publishedBpm, publishedPlaying, System.nanoTime());
            } catch (RuntimeException error) {
                session = new EditorSession(Graph.demo(), draftBpm, draftPlaying, draftRevision,
                        Graph.demo(), publishedBpm, publishedPlaying, System.nanoTime());
            }
        }
    }

    private static void extractSampleRefs(String json, Set<AssetRef> out) {
        if (json == null || json.isEmpty()) return;
        try {
            var element = com.google.gson.JsonParser.parseString(json);
            scanJsonForSamples(element, out);
        } catch (Exception ignored) {
            var matcher = java.util.regex.Pattern.compile("\"sample\"\\s*:\\s*\\{\\s*\"assetId\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sha256\"\\s*:\\s*\"([a-fA-F0-9]{64})\"").matcher(json);
            while (matcher.find()) {
                out.add(new AssetRef(matcher.group(1), matcher.group(2)));
            }
        }
    }

    private static void scanJsonForSamples(com.google.gson.JsonElement elem, Set<AssetRef> out) {
        if (elem == null) return;
        if (elem.isJsonObject()) {
            var obj = elem.getAsJsonObject();
            if (obj.has("sample") && obj.get("sample").isJsonObject()) {
                var s = obj.getAsJsonObject("sample");
                if (s.has("assetId") && s.has("sha256")) {
                    out.add(new AssetRef(s.get("assetId").getAsString(), s.get("sha256").getAsString()));
                }
            } else if (obj.has("assetId") && obj.has("sha256") && obj.size() == 2) {
                out.add(new AssetRef(obj.get("assetId").getAsString(), obj.get("sha256").getAsString()));
            }
            for (var entry : obj.entrySet()) {
                scanJsonForSamples(entry.getValue(), out);
            }
        } else if (elem.isJsonArray()) {
            for (var child : elem.getAsJsonArray()) {
                scanJsonForSamples(child, out);
            }
        }
    }
}

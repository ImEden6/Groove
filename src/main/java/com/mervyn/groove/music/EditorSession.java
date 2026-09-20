package com.mervyn.groove.music;

import groove.engine.*;

/** Shared last-write-wins draft, separate from the published playback timeline. */
public final class EditorSession {
    private Graph draft, previewGraph;
    private double bpm;
    private boolean playing;
    private long revision, draftAt;
    private double draftCycle;
    private final SessionTimeline committed;

    public EditorSession(Graph draft, double bpm, Graph published, double publishedBpm, long now) {
        this(draft, bpm, false, 0, published, publishedBpm, false, now);
    }
    public EditorSession(Graph draft, double bpm, boolean playing, long revision,
                         Graph published, double publishedBpm, boolean publishedPlaying, long now) {
        validateTempo(bpm);
        if (revision < 0) throw new IllegalArgumentException("Invalid draft revision");
        this.draftAt = now;
        this.playing = playing;
        this.revision = revision;
        this.draft = draft;
        this.previewGraph = SignalGraph.assignBirths(draft, null, now);
        this.bpm = bpm;
        committed = new SessionTimeline(published, publishedBpm, publishedPlaying, now);
    }
    // public EditorSession(long now) { this(Graph.demo(), 128, Graph.demo(), 128, now); }
    public EditorSession(long now) { this(groove.engine.SignalDemo.reverbSources(), 128, groove.engine.SignalDemo.reverbSources(), 128, now); }
    public Graph draft() { return draft; }
    public double bpm() { return bpm; }
    public boolean playing() { return playing; }
    public long revision() { return revision; }
    public SessionTimeline.Snapshot committed(long now) { return committed.snapshot(now); }
    public SessionState preview() { return new SessionState(revision, draftAt, draftCycle, bpm, playing, previewGraph); }
    public void edit(Graph graph, double tempo, boolean play) { edit(graph, tempo, play, System.nanoTime()); }
    public void edit(Graph graph, double tempo, boolean play, long now) {
        validateTempo(tempo);
        // Play and stop reach the committed patch too, which is what linked speakers hear
        if (play != playing) startOrStopCommitted(play, now);
        draftCycle = preview().cycleAt(now);
        draftAt = now;
        previewGraph = SignalGraph.assignBirths(graph, previewGraph, now);
        draft = graph;
        bpm = tempo;
        playing = play;
        revision++;
    }

    /** Keeps the committed patch as it is and only changes whether it plays, at the next safe downbeat.
     *  A queued commit carries its own play state, so it is left to land rather than refusing the draft. */
    private void startOrStopCommitted(boolean play, long now) {
        var before = committed.snapshot(now);
        SessionState state = before.current();
        if (before.pending() != null || state.playing() == play) return;
        committed.schedule(state.graph(), state.bpm(), play, before.revision(), now);
    }
    public void commit(long expected, long now) {
        if (expected != revision) throw new IllegalArgumentException("Draft changed; review it before committing");
        // This commit carries the play state itself, so a queued play change can make way for it
        committed.cancelPendingPlayChange(now);
        var before = committed.snapshot(now);
        committed.schedule(draft, bpm, playing, before.revision(), now);
    }
    private static void validateTempo(double bpm) {
        if (!Double.isFinite(bpm) || bpm < 30 || bpm > 300)
            throw new IllegalArgumentException("BPM must be 30–300");
    }
}

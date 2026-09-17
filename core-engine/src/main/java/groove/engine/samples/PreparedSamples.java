package groove.engine.samples;

import java.util.*;

/** Immutable bounded bank prepared off the audio thread, shared by all sources in a program. */
public final class PreparedSamples {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    public static final int MAX_VOICES = 128;
    private record RegionKey(SampleData source, int start, int end, boolean reverse) {}
    private final Map<SampleVoice, SamplePlayback> voices;
    private final long bytes;

    public PreparedSamples(Map<AssetRef, SampleData> samples, Collection<SampleVoice> settings) {
        this(samples, settings, MAX_BYTES);
    }
    /** Smaller budgets are useful for constrained hosts; the global maximum cannot be raised. */
    public PreparedSamples(Map<AssetRef, SampleData> samples, Collection<SampleVoice> settings, long budget) {
        if (budget < 1 || budget > MAX_BYTES || settings.size() > MAX_VOICES || samples.size() > SampleCatalog.MAX_ASSETS)
            throw new IllegalArgumentException("Sample bank exceeds bounds");
        long used = 0;
        Set<SampleData> originals = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SampleData pcm : samples.values()) {
            Objects.requireNonNull(pcm);
            if (originals.add(pcm)) used += pcm.bytes();
        }
        if (used > budget) throw new IllegalArgumentException("Prepared sample memory budget exceeded");
        Map<RegionKey, SampleData> regions = new HashMap<>();
        Map<SampleVoice, SamplePlayback> result = new HashMap<>();
        for (SampleVoice voice : settings) {
            if (result.containsKey(voice)) continue;
            SampleData source = samples.get(voice.asset());
            if (source == null) continue; // Live missing-asset behavior remains silent.
            SampleRegion region = voice.region();
            int start = region.start(source), end = region.end(source);
            RegionKey key = new RegionKey(source, start, end, region.reverse());
            SampleData pcm = regions.get(key);
            if (pcm == null) {
                if (start == 0 && end == source.frames() && !region.reverse()) pcm = source;
                else {
                    long needed = SampleData.storageBytes(end - start, source.channels());
                    if (needed > budget - used) throw new IllegalArgumentException("Prepared sample memory budget exceeded");
                    pcm = source.copyRegion(start, end, region.reverse());
                    used += needed;
                }
                regions.put(key, pcm);
            }
            result.put(voice, new SamplePlayback(voice, pcm));
        }
        voices = Map.copyOf(result); bytes = used;
    }
    public SamplePlayback get(SampleVoice voice) { return voices.get(voice); }
    /** Single source of truth for voice duration. Missing asset returns -1. */
    public double lifetimeSeconds(SampleVoice voice, double eventCycles, double secondsPerCycle) {
        if (voice == null) return -1;
        SamplePlayback playback = voices.get(voice);
        if (playback == null) return -1;
        return playback.duration();
    }
    /** PCM payload, including source arrays and every isolated region's prefiltered levels. */
    public long bytes() { return bytes; }
}

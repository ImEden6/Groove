package groove.engine;

import java.util.List;

/** Compiled pattern with a first-cycle preview. Hand-built plans remain periodic fixtures. */
public final class LoopPlan {
    private final Event[] events;
    private final Pattern pattern;
    private final SignalGraph signals;
    private final int eventCost;
    private final java.util.Set<groove.engine.samples.SampleVoice> sampleVoices;
    java.util.Set<groove.engine.samples.SampleVoice> sampleVoices() { return sampleVoices; }
    int eventCost() { return eventCost; }
    SignalGraph signals() { return signals; }
    LoopPlan withSignals(SignalGraph value) { return new LoopPlan(java.util.Arrays.asList(events), pattern, value, eventCost, sampleVoices); }
    LoopPlan withSampleVoices(java.util.Set<groove.engine.samples.SampleVoice> value) {
        return new LoopPlan(java.util.Arrays.asList(events), pattern, signals, eventCost, value);
    }
    LoopPlan(List<Event> events) { this(events, null); }
    LoopPlan(List<Event> events, Pattern pattern) { this(events, pattern, null, events.size()); }
    LoopPlan(List<Event> events, Pattern pattern, int eventCost) { this(events, pattern, null, eventCost); }
    private LoopPlan(List<Event> events, Pattern pattern, SignalGraph signals, int eventCost) {
        this(events, pattern, signals, eventCost, events.stream().map(Event::sample).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
    }
    private LoopPlan(List<Event> events, Pattern pattern, SignalGraph signals, int eventCost, java.util.Set<groove.engine.samples.SampleVoice> sampleVoices) {
        this.events = events.toArray(Event[]::new); this.pattern = pattern; this.signals = signals; this.eventCost = eventCost;
        this.sampleVoices = java.util.Set.copyOf(sampleVoices);
    }
    Pattern pattern() { return pattern; }
    public int size() { return events.length; }
    public Event event(int index) { return events[index]; }
}

package groove.engine;

import java.util.List;

/** Compiled pattern with a first-cycle preview. Hand-built plans remain periodic fixtures. */
public final class LoopPlan {
    private final Event[] events;
    private final Pattern pattern;
    private final SignalGraph signals;
    private final int eventCost;
    int eventCost() { return eventCost; }
    SignalGraph signals() { return signals; }
    LoopPlan withSignals(SignalGraph value) { return new LoopPlan(java.util.Arrays.asList(events), pattern, value, eventCost); }
    LoopPlan(List<Event> events) { this(events, null); }
    LoopPlan(List<Event> events, Pattern pattern) { this(events, pattern, null, events.size()); }
    LoopPlan(List<Event> events, Pattern pattern, int eventCost) { this(events, pattern, null, eventCost); }
    private LoopPlan(List<Event> events, Pattern pattern, SignalGraph signals, int eventCost) {
        this.events = events.toArray(Event[]::new); this.pattern = pattern; this.signals = signals; this.eventCost = eventCost;
    }
    Pattern pattern() { return pattern; }
    public int size() { return events.length; }
    public Event event(int index) { return events[index]; }
}

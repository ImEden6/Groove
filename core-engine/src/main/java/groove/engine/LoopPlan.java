package groove.engine;

import java.util.List;

/** An immutable one-cycle schedule; simultaneous notes remain distinct entries. */
public final class LoopPlan {
    private final Event[] events;
    LoopPlan(List<Event> events) { this.events = events.toArray(Event[]::new); }
    public int size() { return events.length; }
    public Event event(int index) { return events[index]; }
}

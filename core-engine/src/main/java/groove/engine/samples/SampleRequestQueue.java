package groove.engine.samples;

import java.util.*;

/** Client-thread queue: graph refreshes may prune automatic requests, never explicit installs. */
public final class SampleRequestQueue {
    public record Request(AssetRef ref, boolean install) {}
    private final LinkedHashMap<AssetRef, Boolean> pending = new LinkedHashMap<>();
    public boolean add(AssetRef ref, boolean install) {
        if (!pending.containsKey(ref) && pending.size() >= 128) return false;
        pending.merge(ref, install, (before, after) -> before || after);
        return true;
    }
    public void retainGraph(Collection<AssetRef> needed) {
        pending.entrySet().removeIf(entry -> !entry.getValue() && !needed.contains(entry.getKey()));
    }
    public Request poll() {
        var iterator = pending.entrySet().iterator();
        if (!iterator.hasNext()) return null;
        var next = iterator.next();
        Request result = new Request(next.getKey(), next.getValue());
        iterator.remove();
        return result;
    }
    public void clear() { pending.clear(); }
}

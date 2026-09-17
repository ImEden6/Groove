package groove.engine;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

final class AllocHelper {
    private AllocHelper() {}

    static ThreadMXBean bean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean counter && counter.isThreadAllocatedMemorySupported()) {
            if (!counter.isThreadAllocatedMemoryEnabled()) {
                counter.setThreadAllocatedMemoryEnabled(true);
            }
            return counter;
        }
        if (Boolean.getBoolean("groove.allowNoAllocCheck")) {
            System.out.println("allocation check skipped");
            return null;
        }
        throw new AssertionError("Thread allocated memory measurement is unsupported on this JVM");
    }
}

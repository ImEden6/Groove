package com.mervyn.groove.music;

public final class TestSupport {
    private TestSupport() {}

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void reject(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}

package com.mervyn.groove.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Disk access and decoding run on the ordered persistence worker. */
final class SessionReads {
    record Result(SessionStore.Saved saved, Exception error) {}

    static void enqueue(Path root, boolean startup, Executor worker, Executor owner,
                        Consumer<Result> completion) {
        worker.execute(() -> {
            Result result;
            try {
                boolean absent = startup && !Files.exists(root.resolve(SessionStore.FILE))
                        && !Files.exists(root.resolve("groove-patch.json"));
                result = new Result(absent ? null : SessionStore.read(root), null);
            } catch (Exception error) {
                result = new Result(null, error);
            }
            Result finished = result;
            owner.execute(() -> completion.accept(finished));
        });
    }
}

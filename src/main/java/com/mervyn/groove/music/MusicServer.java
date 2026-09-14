package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import groove.engine.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.util.*;

/**
 * One world-wide session for the backend prototype. All edits require operator
 * level 2.
 */
public final class MusicServer {
    private static MusicServer active;
    private final MinecraftServer server;
    private final UUID epoch = UUID.randomUUID();
    private final Map<UUID, Long> pings = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> submissions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path saveRoot;
    private SessionTimeline timeline;
    private final java.util.concurrent.ThreadPoolExecutor saves = newSaveExecutor();

    static java.util.concurrent.ThreadPoolExecutor newSaveExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16),
                task -> {
                    Thread thread = new Thread(task, "Groove saves");
                    thread.setDaemon(true);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    private MusicServer(MinecraftServer server) {
        this.server = server;
        saveRoot = server.getWorldPath(LevelResource.ROOT);
        Graph graph = Graph.demo();
        double bpm = 128;
        try {
            if (Files.exists(saveRoot.resolve(SessionStore.FILE))
                    || Files.exists(saveRoot.resolve("groove-patch.json"))) {
                var saved = SessionStore.read(saveRoot);
                graph = saved.graph();
                bpm = saved.bpm();
            }
        } catch (Exception error) {
            GrooveMod.LOGGER.warn("Could not load Groove patch; using available defaults", error);
        }
        timeline = new SessionTimeline(graph, bpm, System.nanoTime());
    }

    public static void register() {
        MusicPackets.register();
        EditorServer.register();
        SampleServer.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> active = new MusicServer(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MusicServer session = active;
            active = null;
            if (session != null) {
                session.saves.shutdown();
                try {
                    if (!session.saves.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
                        GrooveMod.LOGGER.warn("Groove saves still pending after shutdown timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (active != null)
                active.send(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (active != null)
                active.submissions.remove(handler.player.getUUID());
            if (active != null)
                active.pings.remove(handler.player.getUUID());
        });
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.Submit.TYPE, (packet, context) -> {
            context.server().execute(() -> {
                if (active == null)
                    return;
                var player = context.player();
                boolean accepted = false;
                String message;
                try {
                    long now = System.nanoTime();
                    if (!player.hasPermissions(2))
                        throw new IllegalArgumentException("Editing requires operator level 2");
                    Long last = active.submissions.get(player.getUUID());
                    if (last != null && now - last < 250_000_000L)
                        throw new IllegalArgumentException("Please wait before submitting again");
                    active.submissions.put(player.getUUID(), now);
                    if (!active.epoch.equals(packet.epoch()))
                        throw new IllegalArgumentException("Session changed; reload the editor");
                    var before = active.timeline.snapshot(now);
                    if (before.revision() != packet.revision())
                        throw new IllegalArgumentException("Another edit arrived; reload the session before applying");
                    Graph graph = GraphJson.decode(packet.graph());
                    for (var node : graph.nodes())
                        if (node.sample() != null && !SampleServer.hasAsset(node.sample()))
                            throw new IllegalArgumentException("Server lacks exact sample " + node.sample().assetId()
                                    + "; install the matching file in world/ sequencer_samples first");
                    active.timeline.schedule(graph, packet.bpm(), packet.playing(), packet.revision(), now);
                    accepted = true;
                    message = "Accepted; queued for the safe downbeat. /groove save persists it.";
                    for (ServerPlayer listener : active.server.getPlayerList().getPlayers())
                        active.send(listener);
                } catch (IllegalArgumentException error) {
                    message = error.getMessage();
                }
                active.send(player);
                if (ServerPlayNetworking.canSend(player, MusicPackets.SubmitResult.TYPE)) {
                    ServerPlayNetworking.send(player, new MusicPackets.SubmitResult(packet.request(), accepted,
                            message.substring(0, Math.min(512, message.length()))));
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.Ping.TYPE, (packet, context) -> {
            if (active == null)
                return;
            long now = System.nanoTime();
            Long last = active.pings.get(context.player().getUUID());
            if (last != null && now - last < 500_000_000L)
                return;
            active.pings.put(context.player().getUUID(), now);
            ServerPlayNetworking.send(context.player(), new MusicPackets.Pong(packet.sent(), now));
            // Periodic full snapshots also recover a missed join snapshot or a client
            // resource reload.
            active.send(context.player());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("groove")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.literal("play").requires(s -> s.hasPermission(2))
                                .executes(ctx -> change(ctx.getSource(), "play", 0)))
                        .then(Commands.literal("stop").requires(s -> s.hasPermission(2))
                                .executes(ctx -> change(ctx.getSource(), "stop", 0)))
                        .then(Commands.literal("demo").requires(s -> s.hasPermission(2))
                                .executes(ctx -> change(ctx.getSource(), "demo", 0)))
                        .then(Commands.literal("sample-demo").requires(s -> s.hasPermission(2))
                                .executes(ctx -> change(ctx.getSource(), "sample-demo", 0)))
                        .then(Commands.literal("signal-demo").requires(s -> s.hasPermission(2))
                                .executes(ctx -> change(ctx.getSource(), "signal-demo", 0)))
                        .then(Commands.literal("load").requires(s -> s.hasPermission(2))
                                .executes(ctx -> change(ctx.getSource(), "load", 0)))
                        .then(Commands.literal("save").requires(s -> s.hasPermission(2))
                                .executes(ctx -> save(ctx.getSource())))
                        .then(Commands.literal("tempo").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("bpm", DoubleArgumentType.doubleArg(30, 300))
                                        .executes(ctx -> change(ctx.getSource(), "tempo",
                                                DoubleArgumentType.getDouble(ctx, "bpm")))))));
    }

    private void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, MusicPackets.Snapshot.TYPE))
            ServerPlayNetworking.send(player, new MusicPackets.Snapshot(epoch, timeline.snapshot(System.nanoTime())));
    }

    private static int status(CommandSourceStack source) {
        if (active == null)
            return 0;
        var snapshot = active.timeline.snapshot(System.nanoTime());
        var state = snapshot.current();
        source.sendSuccess(() -> Component.literal("Groove: " + (state.playing() ? "playing" : "stopped")
                + ", " + state.bpm() + " BPM, revision " + snapshot.revision()
                + (snapshot.pending() == null ? "" : " (change queued)")
                + ". Commands: play, stop, demo, sample-demo, signal-demo, tempo <bpm>, save, load."), false);
        return 1;
    }

    private static int change(CommandSourceStack source, String action, double tempo) {
        if (active == null)
            return 0;
        try {
            long now = System.nanoTime();
            var before = active.timeline.snapshot(now);
            var state = before.current();
            var loaded = action.equals("load") ? SessionStore.read(active.saveRoot) : null;
            Graph graph = loaded != null ? loaded.graph()
                    : action.equals("demo") ? Graph.demo()
                            : action.equals("signal-demo") ? groove.engine.SignalDemo.multipleSources()
                            : action.equals("sample-demo") ? groove.engine.samples.FactorySamples.demo()
                                    : state.graph();
            boolean playing = action.equals("play") || (!action.equals("stop") && state.playing());
            active.timeline.schedule(graph,
                    loaded != null ? loaded.bpm() : action.equals("tempo") ? tempo : state.bpm(), playing,
                    before.revision(), now);
            for (ServerPlayer player : active.server.getPlayerList().getPlayers())
                active.send(player);
            source.sendSuccess(
                    () -> Component
                            .literal("Groove change queued for the next safe downbeat. Use /groove save to persist."),
                    true);
            return 1;
        } catch (Exception error) {
            source.sendFailure(Component.literal("Groove: " + error.getMessage()));
            return 0;
        }
    }

    static boolean allowsAsset(groove.engine.samples.AssetRef ref) {
        if (active == null)
            return false;
        var snapshot = active.timeline.snapshot(System.nanoTime());
        return snapshot.current().graph().nodes().stream().anyMatch(n -> ref.equals(n.sample()))
                || snapshot.pending() != null
                        && snapshot.pending().graph().nodes().stream().anyMatch(n -> ref.equals(n.sample()));
    }

    private static int save(CommandSourceStack source) {
        if (active == null)
            return 0;
        MusicServer session = active;
        var snapshot = session.timeline.snapshot(System.nanoTime());
        var state = snapshot.pending() == null ? snapshot.current() : snapshot.pending();
        try {
            session.saves.execute(() -> {
                String result;
                boolean success;
                try {
                    SessionStore.write(session.saveRoot, new SessionStore.Saved(state.graph(), state.bpm()));
                    result = "Saved patch and tempo together in groove-session.json.";
                    success = true;
                } catch (Exception error) {
                    result = "Groove save failed: " + error.getMessage();
                    success = false;
                    GrooveMod.LOGGER.warn("Groove save failed", error);
                }
                final String message = result;
                final boolean saved = success;
                session.server.execute(() -> {
                    if (active != session)
                        return;
                    if (saved)
                        source.sendSuccess(() -> Component.literal(message), false);
                    else
                        source.sendFailure(Component.literal(message));
                });
            });
            source.sendSuccess(() -> Component.literal("Groove save queued."), false);
            return 1;
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            source.sendFailure(Component.literal("Groove save queue is full or stopping; try again later."));
            return 0;
        }
    }
}

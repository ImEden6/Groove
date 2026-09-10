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
import java.io.IOException;
import java.util.*;

/** One world-wide session for the backend prototype. All edits require operator level 2. */
public final class MusicServer {
    private static MusicServer active;
    private final MinecraftServer server;
    private final UUID epoch = UUID.randomUUID();
    private final Map<UUID, Long> pings = new HashMap<>();
    private final Map<UUID, Long> submissions = new HashMap<>();
    private final Path patchFile;
    private final Path tempoFile;
    private SessionTimeline timeline;

    private MusicServer(MinecraftServer server) {
        this.server = server;
        patchFile = server.getWorldPath(LevelResource.ROOT).resolve("groove-patch.json");
        tempoFile = server.getWorldPath(LevelResource.ROOT).resolve("groove-tempo.txt");
        Graph graph = Graph.demo();
        double bpm = 128;
        try {
            if (Files.exists(patchFile)) graph = readPatch();
            if (Files.exists(tempoFile) && Files.size(tempoFile) < 64) bpm = Double.parseDouble(Files.readString(tempoFile).trim());
            if (!Double.isFinite(bpm) || bpm < 30 || bpm > 300) bpm = 128;
        } catch (Exception error) { GrooveMod.LOGGER.warn("Could not load Groove patch; using available defaults", error); }
        timeline = new SessionTimeline(graph, bpm, System.nanoTime());
    }

    public static void register() {
        MusicPackets.register();
        SampleServer.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> active = new MusicServer(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> active = null);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (active != null) active.send(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (active != null) active.submissions.remove(handler.player.getUUID());
            if (active != null) active.pings.remove(handler.player.getUUID());
        });
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.Submit.TYPE, (packet, context) -> {
            if (active == null) return;
            var player = context.player();
            boolean accepted = false;
            String message;
            try {
                long now = System.nanoTime();
                if (!player.hasPermissions(2)) throw new IllegalArgumentException("Editing requires operator level 2");
                Long last = active.submissions.put(player.getUUID(), now);
                if (last != null && now - last < 250_000_000L) throw new IllegalArgumentException("Please wait before submitting again");
                if (!active.epoch.equals(packet.epoch())) throw new IllegalArgumentException("Session changed; reload the editor");
                var before = active.timeline.snapshot(now);
                if (before.revision() != packet.revision()) throw new IllegalArgumentException("Another edit arrived; reload the session before applying");
                Graph graph = GraphJson.decode(packet.graph());
                for (var node : graph.nodes()) if (node.sample() != null && !SampleServer.hasAsset(node.sample()))
                    throw new IllegalArgumentException("Server lacks exact sample " + node.sample().assetId() + "; install the matching file in world/ sequencer_samples first");
                active.timeline.schedule(graph, packet.bpm(), packet.playing(), packet.revision(), now);
                accepted = true;
                message = "Accepted; queued for the safe downbeat. /groove save persists it.";
                for (ServerPlayer listener : active.server.getPlayerList().getPlayers()) active.send(listener);
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
            active.send(player);
            ServerPlayNetworking.send(player, new MusicPackets.SubmitResult(packet.request(), accepted,
                    message.substring(0, Math.min(512, message.length()))));
        });
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.Ping.TYPE, (packet, context) -> {
            if (active == null) return;
            long now = System.nanoTime();
            Long last = active.pings.get(context.player().getUUID());
            if (last != null && now - last < 500_000_000L) return;
            active.pings.put(context.player().getUUID(), now);
            ServerPlayNetworking.send(context.player(), new MusicPackets.Pong(packet.sent(), now));
            // Periodic full snapshots also recover a missed join snapshot or a client resource reload.
            active.send(context.player());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("groove")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.literal("play").requires(s -> s.hasPermission(2)).executes(ctx -> change(ctx.getSource(), "play", 0)))
                        .then(Commands.literal("stop").requires(s -> s.hasPermission(2)).executes(ctx -> change(ctx.getSource(), "stop", 0)))
                        .then(Commands.literal("demo").requires(s -> s.hasPermission(2)).executes(ctx -> change(ctx.getSource(), "demo", 0)))
                        .then(Commands.literal("sample-demo").requires(s -> s.hasPermission(2)).executes(ctx -> change(ctx.getSource(), "sample-demo", 0)))
                        .then(Commands.literal("load").requires(s -> s.hasPermission(2)).executes(ctx -> change(ctx.getSource(), "load", 0)))
                        .then(Commands.literal("save").requires(s -> s.hasPermission(2)).executes(ctx -> save(ctx.getSource())))
                        .then(Commands.literal("tempo").requires(s -> s.hasPermission(2)).then(Commands.argument("bpm", DoubleArgumentType.doubleArg(30, 300))
                                .executes(ctx -> change(ctx.getSource(), "tempo", DoubleArgumentType.getDouble(ctx, "bpm")))))));
    }

    private void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, MusicPackets.Snapshot.TYPE))
            ServerPlayNetworking.send(player, new MusicPackets.Snapshot(epoch, timeline.snapshot(System.nanoTime())));
    }
    private static int status(CommandSourceStack source) {
        if (active == null) return 0;
        var snapshot = active.timeline.snapshot(System.nanoTime());
        var state = snapshot.current();
        source.sendSuccess(() -> Component.literal("Groove: " + (state.playing() ? "playing" : "stopped")
                + ", " + state.bpm() + " BPM, revision " + snapshot.revision()
                + (snapshot.pending() == null ? "" : " (change queued)")
                + ". Commands: play, stop, demo, tempo <bpm>, save, load."), false);
        return 1;
    }
    private static int change(CommandSourceStack source, String action, double tempo) {
        if (active == null) return 0;
        try {
            long now = System.nanoTime();
            var before = active.timeline.snapshot(now);
            var state = before.current();
            Graph graph = action.equals("load") ? active.readPatch() : action.equals("demo") ? Graph.demo()
                    : action.equals("sample-demo") ? groove.engine.samples.FactorySamples.demo() : state.graph();
            boolean playing = action.equals("play") || (!action.equals("stop") && state.playing());
            active.timeline.schedule(graph, action.equals("tempo") ? tempo : state.bpm(), playing, before.revision(), now);
            for (ServerPlayer player : active.server.getPlayerList().getPlayers()) active.send(player);
            source.sendSuccess(() -> Component.literal("Groove change queued for the next safe downbeat. Use /groove save to persist."), true);
            return 1;
        } catch (Exception error) {
            source.sendFailure(Component.literal("Groove: " + error.getMessage()));
            return 0;
        }
    }
    private Graph readPatch() throws IOException {
        if (Files.size(patchFile) > GraphJson.MAX_LENGTH) throw new IOException("Patch file exceeds 32 KiB");
        return GraphJson.decode(Files.readString(patchFile));
    }
    static boolean allowsAsset(groove.engine.samples.AssetRef ref) {
        if (active == null) return false;
        var snapshot = active.timeline.snapshot(System.nanoTime());
        return snapshot.current().graph().nodes().stream().anyMatch(n -> ref.equals(n.sample()))
                || snapshot.pending() != null && snapshot.pending().graph().nodes().stream().anyMatch(n -> ref.equals(n.sample()));
    }
    private static int save(CommandSourceStack source) {
        if (active == null) return 0;
        try {
            var snapshot = active.timeline.snapshot(System.nanoTime());
            var state = snapshot.pending() == null ? snapshot.current() : snapshot.pending();
            atomicWrite(active.patchFile, GraphJson.encode(state.graph()));
            atomicWrite(active.tempoFile, Double.toString(state.bpm()));
            source.sendSuccess(() -> Component.literal("Saved groove-patch.json and groove-tempo.txt in the world folder."), false);
            return 1;
        } catch (IOException error) { source.sendFailure(Component.literal("Groove save failed: " + error.getMessage())); return 0; }
    }
    private static void atomicWrite(Path target, String value) throws IOException {
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "groove-", ".tmp");
        try {
            Files.writeString(temporary, value);
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}

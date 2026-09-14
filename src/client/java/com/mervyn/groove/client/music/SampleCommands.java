package com.mervyn.groove.client.music;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/** Backend controls for the future sample drawer. */
public final class SampleCommands {
    private static volatile long auditionTicket;
    private static GrooveSound sound;
    private static AuditionStream stream;
    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(literal("groove-samples")
                .then(literal("list").executes(ctx -> list(ctx.getSource(), ""))
                        .then(argument("search", StringArgumentType.greedyString()).executes(ctx -> list(ctx.getSource(), StringArgumentType.getString(ctx, "search")))))
                .then(literal("reload").executes(ctx -> { SampleLibrary.executor().execute(() -> { SampleLibrary.reload(); Minecraft.getInstance().execute(MusicClient::refreshSamples); }); return 1; }))
                .then(literal("stop").executes(ctx -> { stop(); return 1; }))
                .then(literal("status").executes(ctx -> {
                    MusicClient.sampleStatus().forEach((ref, status) -> ctx.getSource().sendFeedback(Component.literal(ref.assetId() + ": " + status)));
                    SampleLibrary.errors().forEach((ref, status) -> ctx.getSource().sendFeedback(Component.literal(ref.assetId() + ": " + status)));
                    SampleLibrary.catalog().warnings().forEach(message -> ctx.getSource().sendFeedback(Component.literal(message)));
                    return 1;
                }))
                .then(literal("install").then(argument("asset", StringArgumentType.greedyString()).executes(ctx -> {
                    String id = StringArgumentType.getString(ctx, "asset");
                    var ref = SampleLibrary.remoteAvailable().stream().filter(candidate -> candidate.assetId().equals(id)).findFirst().orElse(null);
                    if (ref == null) { ctx.getSource().sendError(Component.literal("Not found in server catalog: " + id)); return 0; }
                    try { SampleLibrary.install(ref); }
                    catch (IllegalArgumentException error) { ctx.getSource().sendError(Component.literal(error.getMessage())); return 0; }
                    ctx.getSource().sendFeedback(Component.literal("Installing " + id + "...")); return 1;
                })))
                .then(literal("ref").then(argument("asset", StringArgumentType.greedyString()).executes(ctx -> {
                    var entry = SampleLibrary.catalog().find(StringArgumentType.getString(ctx, "asset"));
                    if (entry == null) { ctx.getSource().sendError(Component.literal("Sample not found")); return 0; }
                    String json = "{\"assetId\":\"" + entry.ref().assetId() + "\",\"sha256\":\"" + entry.ref().sha256() + "\"}";
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    ctx.getSource().sendFeedback(Component.literal("Copied sample reference: " + json)); return 1;
                })))
                .then(literal("audition").then(argument("asset", StringArgumentType.greedyString()).executes(ctx -> {
                    var entry = SampleLibrary.catalog().find(StringArgumentType.getString(ctx, "asset"));
                    if (entry == null) { ctx.getSource().sendError(Component.literal("Sample not found")); return 0; }
                    stop(); long ticket = auditionTicket;
                    SampleLibrary.executor().execute(() -> {
                        if (ticket != auditionTicket) return;
                        try {
                            var pcm = SampleLibrary.resolve(entry.ref());
                            Minecraft.getInstance().execute(() -> {
                                if (ticket != auditionTicket) return;
                                stream = new AuditionStream(entry.ref(), pcm); sound = new GrooveSound(stream);
                                Minecraft.getInstance().getSoundManager().play(sound);
                            });
                        } catch (RuntimeException error) {
                            Minecraft.getInstance().execute(() -> { if (ticket == auditionTicket) ctx.getSource().sendError(Component.literal(error.getMessage())); });
                        }
                    });
                    return 1;
                })))));
    }
    private static int list(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String search) {
        String query = search.toLowerCase(java.util.Locale.ROOT);
        var catalog = SampleLibrary.catalog();
        var matches = catalog.entries().stream().filter(e -> e.ref().assetId().contains(query)).toList();
        matches.stream().limit(20).forEach(e -> source.sendFeedback(Component.literal(e.ref().assetId() + " (" + e.size() + " bytes)")));
        var remoteOnly = SampleLibrary.remoteAvailable().stream()
                .filter(ref -> ref.assetId().contains(query) && catalog.status(ref) != groove.engine.samples.SampleCatalog.Status.READY)
                .toList();
        remoteOnly.stream().limit(20).forEach(ref -> source.sendFeedback(Component.literal(ref.assetId() + " (not installed, run install " + ref.assetId() + ")")));
        source.sendFeedback(Component.literal(matches.size() + " local match(es), " + remoteOnly.size()
                + " available on the server not yet installed; showing up to 20 each")); return 1;
    }
    public static void stop() {
        auditionTicket++;
        if (sound != null) Minecraft.getInstance().getSoundManager().stop(sound);
        if (stream != null) stream.close(); sound = null; stream = null;
    }
    public static boolean auditioning() { return sound != null && Minecraft.getInstance().getSoundManager().isActive(sound); }
    public static void audition(groove.engine.samples.AssetRef ref, java.util.function.Consumer<String> feedback) {
        stop(); long ticket = auditionTicket;
        SampleLibrary.executor().execute(() -> {
            try {
                var pcm = SampleLibrary.resolve(ref);
                Minecraft.getInstance().execute(() -> {
                    if (ticket != auditionTicket) return;
                    stream = new AuditionStream(ref, pcm); sound = new GrooveSound(stream);
                    Minecraft.getInstance().getSoundManager().play(sound);
                });
            } catch (RuntimeException error) {
                Minecraft.getInstance().execute(() -> { if (ticket == auditionTicket) feedback.accept(error.getMessage()); });
            }
        });
    }
}

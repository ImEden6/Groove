package com.mervyn.groove.client.ui;

import com.mervyn.groove.client.ui.theme.ClockworkRenderer;
import com.mervyn.groove.client.ui.theme.CrtRenderer;
import com.mervyn.groove.client.ui.theme.NoOpThemeRenderer;
import com.mervyn.groove.client.ui.theme.TacticalRenderer;
import com.mervyn.groove.client.ui.theme.ThemeRenderer;
import com.mervyn.groove.client.ui.theme.VanillaRenderer;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Dev entry point for the editor screen, ahead of it being reachable from real game UI. */
public final class EditorCommands {
    private static final Map<String, ThemeRenderer> RENDERERS = Map.of(
            "tactical", new TacticalRenderer(),
            "clockwork", new ClockworkRenderer(),
            "crt", new CrtRenderer(),
            "vanilla", new VanillaRenderer(),
            "noop", new NoOpThemeRenderer());

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("groove-editor")
                        .executes(ctx -> open(new VanillaRenderer()))
                        .then(argument("theme", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(RENDERERS.keySet(), builder))
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "theme");
                                    ThemeRenderer renderer = RENDERERS.get(name);
                                    if (renderer == null) {
                                        ctx.getSource().sendError(Component.literal("Unknown theme: " + name + ". Try tactical, clockwork, crt, vanilla, or noop."));
                                        return 0;
                                    }
                                    return open(renderer);
                                }))));
    }

    static int open(ThemeRenderer renderer) {
        var session = com.mervyn.groove.client.music.MusicClient.desiredState();
        if (session == null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal("Waiting for the Groove session; try again shortly."), false);
            return 0;
        }
        // tell() unconditionally queues the task for a later pass of the main loop.
        // execute() looked like it would defer too, but on the main thread outside an
        // already queued task it just runs inline immediately, same timing as calling
        // setScreen directly, so it never actually avoided the chat screen's own close.
        Minecraft.getInstance().tell(() -> {
            try {
                Minecraft.getInstance().setScreen(new GrooveEditorScreen(session.graph(), renderer));
            } catch (RuntimeException error) {
                com.mervyn.groove.GrooveMod.LOGGER.error("Failed to open the Groove editor screen", error);
            }
        });
        return 1;
    }
}

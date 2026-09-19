package com.mervyn.groove.music;

import com.mervyn.groove.GrooveMod;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public final class GrooveProtocol {
    public static final int VERSION = 5;

    private GrooveProtocol() {}

    public static String missingChannelMessage() {
        return "This server requires Groove protocol " + VERSION + ". Update Groove.";
    }

    public static String mismatchMessage(int clientVersion) {
        return "Groove version mismatch: server " + VERSION + ", client " + clientVersion;
    }

    public static void handleConfigure(boolean canSendProtocol, String playerName,
                                       Consumer<Component> disconnect,
                                       Consumer<MusicPackets.ProtocolTask> addTask) {
        if (!canSendProtocol) {
            GrooveMod.LOGGER.warn("Groove protocol check failed for {}: missing protocol channel", playerName);
            disconnect.accept(Component.literal(missingChannelMessage()));
            return;
        }
        addTask.accept(new MusicPackets.ProtocolTask(VERSION));
    }

    public static void handlePacket(int clientVersion, String playerName,
                                    Consumer<Component> disconnect,
                                    Runnable onComplete) {
        if (clientVersion != VERSION) {
            GrooveMod.LOGGER.warn("Groove protocol mismatch for {}: server {}, client {}",
                    playerName, VERSION, clientVersion);
            disconnect.accept(Component.literal(mismatchMessage(clientVersion)));
        } else {
            onComplete.run();
        }
    }
}

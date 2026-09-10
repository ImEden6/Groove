package com.mervyn.groove.client;

import net.fabricmc.api.ClientModInitializer;

public class GrooveModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		com.mervyn.groove.client.music.SampleLibrary.register();
		com.mervyn.groove.client.music.SampleCommands.register();
		com.mervyn.groove.client.music.MusicClient.register();
		com.mervyn.groove.client.music.AudioSmokeTest.register();
		com.mervyn.groove.client.ui.EditorCommands.register();
	}
}

package com.mervyn.groove.client;

import com.mervyn.groove.block.GrooveItems;
import com.mervyn.groove.client.item.HeadphonesRenderer;
import dev.emi.trinkets.api.client.TrinketRendererRegistry;
import net.fabricmc.api.ClientModInitializer;

public class GrooveModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		com.mervyn.groove.client.music.SampleLibrary.register();
		com.mervyn.groove.client.music.SampleCommands.register();
		com.mervyn.groove.client.music.MusicClient.register();
		com.mervyn.groove.client.music.AudioSmokeTest.register();
		com.mervyn.groove.client.ui.EditorCommands.register();
		TrinketRendererRegistry.registerRenderer(GrooveItems.HEADPHONES, new HeadphonesRenderer());
	}
}

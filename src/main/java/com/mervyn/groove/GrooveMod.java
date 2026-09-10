package com.mervyn.groove;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrooveMod implements ModInitializer {
	public static final String MOD_ID = "modid";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		com.mervyn.groove.block.GrooveBlocks.register();
		com.mervyn.groove.block.GrooveItems.register();
		com.mervyn.groove.music.MusicServer.register();
		LOGGER.info("Groove music backend initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}

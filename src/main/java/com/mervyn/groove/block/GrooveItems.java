package com.mervyn.groove.block;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.item.HeadphonesItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;

public final class GrooveItems {
    public static final Item SPEAKER = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("speaker"),
            new BlockItem(GrooveBlocks.SPEAKER, new Item.Properties()));

    public static final Item HEADPHONES = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("headphones"),
            new HeadphonesItem(new Item.Properties().stacksTo(1)));

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
            entries.accept(SPEAKER);
            entries.accept(HEADPHONES);
        });
    }

    private GrooveItems() {}
}

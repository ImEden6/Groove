package com.mervyn.groove.block;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.item.DiscCopyRecipe;
import com.mervyn.groove.item.DiscItem;
import com.mervyn.groove.item.HeadphonesItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

public final class GrooveItems {
    public static final Item SPEAKER = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("speaker"),
            new BlockItem(GrooveBlocks.SPEAKER, new Item.Properties()));

    public static final Item EDITOR = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("editor"),
            new BlockItem(GrooveBlocks.EDITOR, new Item.Properties()));

    public static final Item HEADPHONES = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("headphones"),
            new HeadphonesItem(new Item.Properties().stacksTo(1)));

    public static final Item BLANK_DISC = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("blank_disc"),
            new Item(new Item.Properties().stacksTo(16)));

    /** A silent jukebox song, so vanilla jukeboxes accept the disc while speakers play its patch. */
    public static final ResourceKey<JukeboxSong> DISC_SONG = ResourceKey.create(Registries.JUKEBOX_SONG, GrooveMod.id("groove_disc"));

    public static final Item GROOVE_DISC = Registry.register(BuiltInRegistries.ITEM, GrooveMod.id("groove_disc"),
            new DiscItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON).jukeboxPlayable(DISC_SONG)));

    public static final RecipeSerializer<DiscCopyRecipe> DISC_COPY = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            GrooveMod.id("disc_copy"), new SimpleCraftingRecipeSerializer<>(DiscCopyRecipe::new));

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
            entries.accept(SPEAKER);
            entries.accept(EDITOR);
            entries.accept(HEADPHONES);
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(BLANK_DISC);
            entries.accept(GROOVE_DISC);
        });
    }

    private GrooveItems() {}
}

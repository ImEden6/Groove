package com.mervyn.groove.music;

import com.mervyn.groove.item.DiscCopyRecipe;
import groove.engine.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import java.util.List;
import java.util.Map;
import static com.mervyn.groove.music.TestSupport.*;

final class DiscTests {
    static void run() {
        // Vanilla stand-ins for the two disc items, as the harness cannot register mod items
        var disc = Items.MUSIC_DISC_CAT;
        var blank = Items.PAPER;
        Graph graph = SignalGraph.assignBirths(SignalDemo.reverbSources(), null, 123_456_789L);
        check(graph.nodes().stream().anyMatch(n -> n.birthNanos() != null), "The demo patch has an LFO birth stamp to strip");
        var burned = new ItemStack(disc);
        check(DiscPatches.read(burned).isEmpty(), "A fresh disc is empty");
        DiscPatches.write(burned, graph, 97.5);
        var patch = DiscPatches.read(burned).orElseThrow();
        check(patch.bpm() == 97.5 && patch.nodes() == graph.nodes().size(), "Disc keeps tempo and node count");
        var played = DiscPatches.graph(patch).orElseThrow();
        check(played.nodes().stream().allMatch(n -> n.birthNanos() == null), "Birth stamps stay with the server that assigned them");
        GraphCompiler.compile(played);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var restored = ItemStack.parse(registries, burned.save(registries)).orElseThrow();
        check(DiscPatches.read(restored).orElseThrow().equals(patch), "Inventory save/reload keeps the patch");

        check(DiscPatches.graph(new DiscPatches.Patch(patch.graph(), 500, 1)).isEmpty(), "An out-of-range tempo is refused");
        check(DiscPatches.graph(new DiscPatches.Patch("{\"version\":3,\"nodes\":[],\"edges\":[]}", 120, 0)).isEmpty(), "An edited, unplayable disc is refused");
        check(DiscPatches.graph(new DiscPatches.Patch("not json", 120, 0)).isEmpty(), "Garbage is refused");

        var empty = new ItemStack(disc);
        check(DiscCopyRecipe.original(CraftingInput.of(2, 1, List.of(burned, new ItemStack(blank))), disc, blank) == 0, "Burned + blank copies");
        check(DiscCopyRecipe.original(CraftingInput.of(3, 1, List.of(new ItemStack(blank), ItemStack.EMPTY, burned)), disc, blank) == 2, "Any order and gaps");
        check(DiscCopyRecipe.original(CraftingInput.of(1, 1, List.of(burned)), disc, blank) < 0, "A blank is needed");
        check(DiscCopyRecipe.original(CraftingInput.of(3, 1, List.of(burned, new ItemStack(blank), new ItemStack(blank))), disc, blank) < 0, "Only one blank at a time");
        check(DiscCopyRecipe.original(CraftingInput.of(2, 1, List.of(empty, new ItemStack(blank))), disc, blank) < 0, "An empty disc has nothing to copy");
        check(DiscCopyRecipe.original(CraftingInput.of(3, 1, List.of(burned, burned.copy(), new ItemStack(blank))), disc, blank) < 0, "Two originals are ambiguous");
        check(DiscCopyRecipe.original(CraftingInput.of(3, 1, List.of(burned, new ItemStack(blank), new ItemStack(Items.STICK))), disc, blank) < 0, "Nothing else in the grid");
        dataFiles();
        System.out.println("Disc checks passed.");
    }
    /** Parses the shipped recipes and jukebox song with vanilla's codecs, mod item ids swapped for vanilla ones. */
    private static void dataFiles() {
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
                net.minecraft.data.registries.VanillaRegistries.createLookup());
        for (String name : List.of("blank_disc", "speaker", "editor", "headphones")) {
            var json = resource("data/modid/recipe/" + name + ".json").replace("\"modid:", "\"minecraft:stone\",\"_was\":\"");
            var parsed = net.minecraft.world.item.crafting.Recipe.CODEC.parse(ops, com.google.gson.JsonParser.parseString(json));
            check(parsed.isSuccess(), "Recipe " + name + " parses: " + parsed.error().map(Object::toString).orElse(""));
        }
        var song = net.minecraft.world.item.JukeboxSong.DIRECT_CODEC.parse(ops,
                com.google.gson.JsonParser.parseString(resource("data/modid/jukebox_song/groove_disc.json")));
        check(song.isSuccess(), "Jukebox song parses: " + song.error().map(Object::toString).orElse(""));
        check(song.getOrThrow().lengthInTicks() > 20 * 60 * 60 * 24 * 365, "The silent song lasts over a year of play");
    }

    private static String resource(String path) {
        try (var in = DiscTests.class.getClassLoader().getResourceAsStream(path)) {
            check(in != null, "Resource " + path + " exists");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private DiscTests() {}
}

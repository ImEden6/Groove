package com.mervyn.groove.gametest;

import com.mervyn.groove.GrooveMod;
import com.mervyn.groove.block.EditorBlockEntity;
import com.mervyn.groove.block.GrooveBlocks;
import com.mervyn.groove.block.GrooveItems;
import com.mervyn.groove.music.DiscPackets;
import com.mervyn.groove.music.DiscPatches;
import com.mervyn.groove.music.DiscServer;
import com.mervyn.groove.music.SpeakerPackets;
import com.mervyn.groove.music.SpeakerServer;
import groove.engine.Graph;
import groove.engine.NodeType;
import groove.engine.SignalDemo;
import groove.engine.samples.AssetRef;
import groove.engine.samples.FactorySamples;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Discs, jukebox towers and recipes in a real server world. Run with gradlew runGametest. */
public class DiscGameTests implements FabricGameTest {
    private static final BlockPos BASE = new BlockPos(1, 1, 1);

    @GameTest(template = EMPTY_STRUCTURE)
    public void burnsTheCommittedPatch(GameTestHelper helper) {
        helper.setBlock(BASE, GrooveBlocks.EDITOR);
        EditorBlockEntity editor = helper.getBlockEntity(BASE);
        ServerPlayer player = player(helper, GameType.SURVIVAL, new ItemStack(GrooveItems.BLANK_DISC, 2));
        long now = System.nanoTime();
        var committed = editor.session().committed(now).current();
        var state = DiscServer.use(player, new DiscPackets.Use(helper.absolutePos(BASE), UUID.randomUUID()), now);
        helper.assertTrue(state.accepted(), "Burn accepted: " + state.message());
        helper.assertTrue(player.getMainHandItem().is(GrooveItems.BLANK_DISC) && player.getMainHandItem().getCount() == 1, "One blank used");
        var patch = DiscPatches.read(disc(player)).orElseThrow();
        helper.assertTrue(patch.bpm() == committed.bpm(), "Disc keeps the committed tempo");
        helper.assertTrue(DiscPatches.graph(patch).orElseThrow().nodes().size() == committed.graph().nodes().size(), "Disc holds the committed patch");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void creativeKeepsTheBlank(GameTestHelper helper) {
        helper.setBlock(BASE, GrooveBlocks.EDITOR);
        ServerPlayer player = player(helper, GameType.CREATIVE, new ItemStack(GrooveItems.BLANK_DISC, 1));
        var state = DiscServer.use(player, new DiscPackets.Use(helper.absolutePos(BASE), UUID.randomUUID()), System.nanoTime());
        helper.assertTrue(state.accepted(), "Burn accepted: " + state.message());
        helper.assertTrue(player.getMainHandItem().is(GrooveItems.BLANK_DISC) && player.getMainHandItem().getCount() == 1, "Creative keeps its blank");
        helper.assertTrue(DiscPatches.read(disc(player)).isPresent(), "And still gets a disc");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void loadingNeedsEditAccess(GameTestHelper helper) {
        helper.setBlock(BASE, GrooveBlocks.EDITOR);
        EditorBlockEntity editor = helper.getBlockEntity(BASE);
        Graph patch = SignalDemo.graph();
        ServerPlayer owner = player(helper, GameType.SURVIVAL, burned(patch, 100));
        ServerPlayer stranger = player(helper, GameType.SURVIVAL, burned(patch, 100));
        editor.setOwner(owner.getUUID());
        Graph before = editor.session().draft();
        long now = System.nanoTime();
        var refused = DiscServer.use(stranger, new DiscPackets.Use(helper.absolutePos(BASE), UUID.randomUUID()), now);
        helper.assertFalse(refused.accepted(), "A player without edit access is refused");
        helper.assertTrue(editor.session().draft().equals(before), "Refusal leaves the draft alone");
        var loaded = DiscServer.use(owner, new DiscPackets.Use(helper.absolutePos(BASE), UUID.randomUUID()), now);
        helper.assertTrue(loaded.accepted(), "The owner loads it: " + loaded.message());
        helper.assertTrue(editor.session().draft().equals(patch) && editor.session().bpm() == 100, "The draft is the disc's patch");
        helper.assertTrue(owner.getMainHandItem().is(GrooveItems.GROOVE_DISC), "Loading keeps the disc");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void refusesAnEditedDisc(GameTestHelper helper) {
        helper.setBlock(BASE, GrooveBlocks.EDITOR);
        EditorBlockEntity editor = helper.getBlockEntity(BASE);
        ServerPlayer owner = player(helper, GameType.SURVIVAL, burned(SignalDemo.graph(), 900));
        editor.setOwner(owner.getUUID());
        var state = DiscServer.use(owner, new DiscPackets.Use(helper.absolutePos(BASE), UUID.randomUUID()), System.nanoTime());
        helper.assertFalse(state.accepted(), "An out-of-range tempo is refused");
        helper.succeed();
    }

    /** A tower on a jukebox plays the disc, keeps one session while it plays, and starts over on reinsertion. */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void jukeboxTowerPlaysTheDisc(GameTestHelper helper) {
        BlockPos speaker = BASE.above();
        helper.setBlock(BASE, Blocks.JUKEBOX);
        helper.setBlock(speaker, GrooveBlocks.SPEAKER);
        JukeboxBlockEntity jukebox = helper.getBlockEntity(BASE);
        ServerPlayer player = player(helper, GameType.SURVIVAL, ItemStack.EMPTY);
        Graph patch = SignalDemo.graph();
        ItemStack disc = burned(patch, 100);
        long start = System.nanoTime();
        helper.assertFalse(poll(helper, player, speaker, start).available(), "An empty jukebox plays nothing");
        jukebox.setTheItem(disc.copy());
        var first = poll(helper, player, speaker, start + 300_000_000L);
        helper.assertTrue(first.available(), "The disc plays");
        helper.assertTrue(first.timeline().current().bpm() == 100 && first.timeline().current().playing(), "At its own tempo");
        var again = poll(helper, player, speaker, start + 600_000_000L);
        helper.assertTrue(again.timeline().epoch().equals(first.timeline().epoch()), "One session while it keeps playing");
        jukebox.setTheItem(ItemStack.EMPTY);
        helper.assertFalse(poll(helper, player, speaker, start + 900_000_000L).available(), "Taking the disc out stops it");
        helper.runAfterDelay(5, () -> {
            jukebox.setTheItem(disc.copy());
            var back = poll(helper, player, speaker, start + 1_200_000_000L);
            helper.assertTrue(back.available() && !back.timeline().epoch().equals(first.timeline().epoch()), "Putting it back starts over");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void discSamplesAreServed(GameTestHelper helper) {
        BlockPos speaker = BASE.above();
        helper.setBlock(BASE, Blocks.JUKEBOX);
        helper.setBlock(speaker, GrooveBlocks.SPEAKER);
        JukeboxBlockEntity jukebox = helper.getBlockEntity(BASE);
        List<String> ids = FactorySamples.ids();
        AssetRef used = FactorySamples.ref(ids.get(0)), unused = FactorySamples.ref(ids.get(1));
        Graph patch = new Graph(3, List.of(new Graph.Node("s", NodeType.GENERATOR_SAMPLE, Map.of(), used, null),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("s", "render"), new Graph.Edge("render", "out", "out", "audio")));
        jukebox.setTheItem(burned(patch, 120));
        ServerPlayer listener = player(helper, GameType.SURVIVAL, ItemStack.EMPTY);
        ServerPlayer stranger = player(helper, GameType.SURVIVAL, ItemStack.EMPTY);
        helper.assertTrue(poll(helper, listener, speaker, System.nanoTime()).available(), "The disc plays");
        helper.assertTrue(SpeakerServer.allowsAsset(listener, used), "A listener may download the disc's sample");
        helper.assertFalse(SpeakerServer.allowsAsset(listener, unused), "But not other samples");
        helper.assertFalse(SpeakerServer.allowsAsset(stranger, used), "Nor may a player who never asked for the tower");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void recipes(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        ItemStack original = burned(SignalDemo.graph(), 110);
        var copyInput = CraftingInput.of(2, 1, List.of(original, new ItemStack(GrooveItems.BLANK_DISC)));
        var copy = recipes.getRecipeFor(RecipeType.CRAFTING, copyInput, level).orElseThrow().value();
        ItemStack result = copy.assemble(copyInput, level.registryAccess());
        helper.assertTrue(result.is(GrooveItems.GROOVE_DISC) && DiscPatches.read(result).equals(DiscPatches.read(original)), "Copy has the patch");
        helper.assertTrue(DiscPatches.read(copy.getRemainingItems(copyInput).get(0)).isPresent(), "The original stays in the grid");
        helper.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING,
                CraftingInput.of(2, 1, List.of(new ItemStack(GrooveItems.GROOVE_DISC), new ItemStack(GrooveItems.BLANK_DISC))), level).isEmpty(),
                "An empty disc can't be copied");
        ItemStack c = new ItemStack(Items.BLACK_CONCRETE), air = ItemStack.EMPTY;
        var blankInput = CraftingInput.of(3, 3, List.of(air, c, air, c, new ItemStack(Items.REDSTONE), c, air, c, air));
        ItemStack blanks = recipes.getRecipeFor(RecipeType.CRAFTING, blankInput, level).orElseThrow().value().assemble(blankInput, level.registryAccess());
        helper.assertTrue(blanks.is(GrooveItems.BLANK_DISC) && blanks.getCount() == 2, "Concrete and redstone make two blanks");
        for (String id : List.of("speaker", "editor", "headphones", "blank_disc", "disc_copy"))
            helper.assertTrue(recipes.byKey(GrooveMod.id(id)).isPresent(), "Recipe " + id + " is loaded");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode, ItemStack held) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        var at = helper.absoluteVec(BASE.getCenter()).add(1.5, 0, 0);
        player.moveTo(at.x, at.y, at.z);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        return player;
    }

    private static ItemStack burned(Graph graph, double bpm) {
        ItemStack disc = new ItemStack(GrooveItems.GROOVE_DISC);
        DiscPatches.write(disc, graph, bpm);
        return disc;
    }

    private static ItemStack disc(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) if (stack.is(GrooveItems.GROOVE_DISC)) return stack;
        throw new AssertionError("No disc in the inventory");
    }

    private static SpeakerPackets.CommittedState poll(GameTestHelper helper, ServerPlayer player, BlockPos speaker, long now) {
        var state = SpeakerServer.committed(player, new SpeakerPackets.CommittedRequest(helper.absolutePos(speaker), UUID.randomUUID()), now);
        if (state == null) throw new AssertionError("Poll was rate-limited");
        return state;
    }
}

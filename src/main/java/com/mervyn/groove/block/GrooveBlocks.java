package com.mervyn.groove.block;

import com.mervyn.groove.GrooveMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

public final class GrooveBlocks {
    public static final Block SPEAKER = register("speaker", SpeakerBlock::new,
            BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops());

    public static final Block EDITOR = register("editor", EditorBlock::new,
            BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops());

    private static Block register(String path, Function<BlockBehaviour.Properties, Block> factory,
                                   BlockBehaviour.Properties properties) {
        return Registry.register(BuiltInRegistries.BLOCK, GrooveMod.id(path), factory.apply(properties));
    }

    /** Forces this class's static initializer (and therefore registration) to run. */
    public static void register() {}

    private GrooveBlocks() {}
}

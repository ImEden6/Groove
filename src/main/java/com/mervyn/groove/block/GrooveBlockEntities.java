package com.mervyn.groove.block;

import com.mervyn.groove.GrooveMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Block entity types owned by Groove blocks. */
public final class GrooveBlockEntities {
    public static final BlockEntityType<SpeakerBlockEntity> SPEAKER = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, GrooveMod.id("speaker"),
            BlockEntityType.Builder.of(SpeakerBlockEntity::new, GrooveBlocks.SPEAKER).build(null));

    /** Forces this class's static initializer (and therefore registration) to run. */
    public static void register() {}

    private GrooveBlockEntities() {}
}

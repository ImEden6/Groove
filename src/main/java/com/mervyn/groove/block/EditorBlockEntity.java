package com.mervyn.groove.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Persistent identity for one editor block. Owner/allowlist and
 * draft/committed session state (see docs/EDITOR-BLOCK-DESIGN.md) are not
 * implemented yet.
 */
public final class EditorBlockEntity extends BlockEntity {
    public EditorBlockEntity(BlockPos pos, BlockState state) {
        super(GrooveBlockEntities.EDITOR, pos, state);
    }
}

package com.mervyn.groove.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Persistent identity for a speaker segment. Client audio promotes only the
 * lowest segment of each contiguous tower into an audible source.
 */
public final class SpeakerBlockEntity extends BlockEntity {
    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(GrooveBlockEntities.SPEAKER, pos, state);
    }
}

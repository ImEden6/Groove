package com.mervyn.groove.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * One block type for the whole speaker tower. PART is never chosen by the
 * player -- it's whatever fits the block directly below (see partFor), which
 * is what makes "base has no ring, everything stacked on it has one" fall
 * out automatically instead of needing separate base/body items.
 */
public final class SpeakerBlock extends HorizontalDirectionalBlock {
    public static final EnumProperty<SpeakerPart> PART = EnumProperty.create("part", SpeakerPart.class);
    private static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

    public SpeakerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, SpeakerPart.BASE));
    }

    @Override protected MapCodec<SpeakerBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(PART, partFor(context.getLevel(), context.getClickedPos()));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN ? state.setValue(PART, partFor(level, pos)) : state;
    }

    private static SpeakerPart partFor(LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(GrooveBlocks.SPEAKER) ? SpeakerPart.BODY : SpeakerPart.BASE;
    }
}

package com.mervyn.groove.block;

import com.mervyn.groove.music.SpeakerServer;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * Placeable, silent authoring/UI trigger for one independent editor session.
 * Session data is stored in the block entity; audio connections are separate.
 */
public final class EditorBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<EditorBlock> CODEC = simpleCodec(EditorBlock::new);

    public EditorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override public void setPlacedBy(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer != null && level.getBlockEntity(pos) instanceof EditorBlockEntity editor) editor.setOwner(placer.getUUID());
    }

    /** Speaker links point at this session by (pos, id); a destroyed session must not leave
     *  them dangling on speakers that will never be relinked automatically. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof EditorBlockEntity editor) {
            SpeakerServer.unlinkAll(serverLevel, pos, editor.sessionId());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** The client opens the session; consume the interaction here so a held block is not placed instead. */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                                   net.minecraft.world.entity.player.Player player,
                                                                   net.minecraft.world.phys.BlockHitResult hit) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    @Override protected MapCodec<EditorBlock> codec() { return CODEC; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EditorBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
}

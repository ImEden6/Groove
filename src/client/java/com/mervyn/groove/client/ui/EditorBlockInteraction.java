package com.mervyn.groove.client.ui;

import com.mervyn.groove.block.GrooveBlocks;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Right-clicking a placed editor block requests its own server-owned editor session. */
public final class EditorBlockInteraction {
    public static void register() {
        BlockEditorClient.register();
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!level.getBlockState(hitResult.getBlockPos()).is(GrooveBlocks.EDITOR)) {
                return InteractionResult.PASS;
            }
            BlockEditorClient.open(hitResult.getBlockPos());
            return InteractionResult.SUCCESS;
        });
    }

    private EditorBlockInteraction() {}
}

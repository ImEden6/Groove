package com.mervyn.groove.client.ui;

import com.mervyn.groove.block.GrooveBlocks;
import com.mervyn.groove.client.ui.theme.VanillaRenderer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Right-clicking a placed editor block opens the same editor screen as /groove-editor. */
public final class EditorBlockInteraction {
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!level.getBlockState(hitResult.getBlockPos()).is(GrooveBlocks.EDITOR)) {
                return InteractionResult.PASS;
            }
            EditorCommands.open(new VanillaRenderer());
            return InteractionResult.SUCCESS;
        });
    }

    private EditorBlockInteraction() {}
}

package com.mervyn.groove.client.ui;

import com.mervyn.groove.block.GrooveBlocks;
import com.mervyn.groove.block.GrooveItems;
import com.mervyn.groove.client.music.HeadphoneBindClient;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Right-clicking a placed editor block opens its session, unless the headphones are held (then it binds them instead). */
public final class EditorBlockInteraction {
    public static void register() {
        BlockEditorClient.register();
        HeadphoneBindClient.register();
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!level.getBlockState(hitResult.getBlockPos()).is(GrooveBlocks.EDITOR)) {
                return InteractionResult.PASS;
            }
            // Sneaking with something in either hand places or uses it, as on any interactive block
            if (player.isSecondaryUseActive() && !(player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty())) {
                return InteractionResult.PASS;
            }
            if (player.getMainHandItem().is(GrooveItems.HEADPHONES)) {
                HeadphoneBindClient.bind(hitResult.getBlockPos());
            } else {
                BlockEditorClient.open(hitResult.getBlockPos());
            }
            return InteractionResult.SUCCESS;
        });
    }

    private EditorBlockInteraction() {}
}

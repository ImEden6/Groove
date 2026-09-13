package com.mervyn.groove.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.client.TrinketRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws the equipped headphones item on the wearer's head. Purely visual, no audio routing yet. */
public class HeadphonesRenderer implements TrinketRenderer {
    @Override
    public void render(ItemStack stack, SlotReference slotReference, EntityModel<? extends LivingEntity> contextModel,
            PoseStack poseStack, MultiBufferSource buffer, int light, LivingEntity entity, float limbAngle,
            float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        if (!(contextModel instanceof HumanoidModel<?> model)) {
            return;
        }

        // Matches vanilla's CustomHeadLayer.translateToHead, the baseline transform it uses for
        // any item worn in the head slot; the model's own display.head transform layers on top
        // of this via ItemDisplayContext.HEAD.
        poseStack.pushPose();
        model.head.translateAndRotate(poseStack);
        poseStack.translate(0, -0.25, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(0.625f, -0.625f, -0.625f);

        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.HEAD, light,
                OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());

        poseStack.popPose();
    }
}

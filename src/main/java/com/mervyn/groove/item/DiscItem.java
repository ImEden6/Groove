package com.mervyn.groove.item;

import com.mervyn.groove.music.DiscPatches;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

public class DiscItem extends Item {
    public DiscItem(Item.Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var patch = DiscPatches.read(stack);
        tooltip.add(patch.map(p -> Component.translatable("item.modid.groove_disc.patch", Math.round(p.bpm()), p.nodes()))
                .orElse(Component.translatable("item.modid.groove_disc.empty")).withStyle(ChatFormatting.GRAY));
    }
}

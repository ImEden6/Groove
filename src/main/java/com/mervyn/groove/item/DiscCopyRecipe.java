package com.mervyn.groove.item;

import com.mervyn.groove.block.GrooveItems;
import com.mervyn.groove.music.DiscPatches;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** A burned disc and a blank one make a copy, and the original stays in the grid, like book cloning. */
public class DiscCopyRecipe extends CustomRecipe {
    public DiscCopyRecipe(CraftingBookCategory category) { super(category); }

    @Override
    public boolean matches(CraftingInput input, Level level) { return original(input) >= 0; }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int slot = original(input);
        return slot < 0 ? ItemStack.EMPTY : input.getItem(slot).copyWithCount(1);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        int slot = original(input);
        if (slot >= 0) remaining.set(slot, input.getItem(slot).copyWithCount(1));
        return remaining;
    }

    private static int original(CraftingInput input) { return original(input, GrooveItems.GROOVE_DISC, GrooveItems.BLANK_DISC); }

    /** The burned disc's slot when the grid holds exactly it and one blank, or -1. */
    public static int original(CraftingInput input, Item disc, Item blank) {
        int burned = -1, blanks = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.is(disc) && DiscPatches.read(stack).isPresent() && burned < 0) burned = i;
            else if (stack.is(blank)) blanks++;
            else return -1;
        }
        return blanks == 1 ? burned : -1;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }

    @Override
    public RecipeSerializer<?> getSerializer() { return GrooveItems.DISC_COPY; }
}

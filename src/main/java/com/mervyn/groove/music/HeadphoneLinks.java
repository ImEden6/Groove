package com.mervyn.groove.music;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.Optional;
import java.util.UUID;

/** The link travels with this pair of headphones, including inventory saves and trades. */
public final class HeadphoneLinks {
    private static final String KEY = "groove:editor_link";
    public record Link(ResourceLocation dimension, BlockPos pos, UUID session) {
        public Link {
            java.util.Objects.requireNonNull(dimension);
            pos = pos.immutable();
            java.util.Objects.requireNonNull(session);
        }
    }
    public static Optional<Link> read(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag link = data.getCompound(KEY);
        ResourceLocation dimension = ResourceLocation.tryParse(link.getString("Dimension"));
        if (dimension == null || !link.hasUUID("Session") || !link.contains("Position", net.minecraft.nbt.Tag.TAG_LONG))
            return Optional.empty();
        return Optional.of(new Link(dimension, BlockPos.of(link.getLong("Position")), link.getUUID("Session")));
    }
    public static boolean inRange(Link link, ResourceLocation dimension, net.minecraft.world.phys.Vec3 position) {
        return link.dimension().equals(dimension) && position.distanceToSqr(link.pos().getCenter()) <= 256;
    }
    public static void clear(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove(KEY);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
    public static void bind(ItemStack stack, Link link) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", link.dimension().toString());
        tag.putLong("Position", link.pos().asLong());
        tag.putUUID("Session", link.session());
        data.put(KEY, tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
    private HeadphoneLinks() {}
}

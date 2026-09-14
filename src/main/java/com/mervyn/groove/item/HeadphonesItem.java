package com.mervyn.groove.item;

import com.mervyn.groove.music.HeadphoneLinks;
import com.mervyn.groove.music.HeadphoneServer;
import dev.emi.trinkets.api.TrinketItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Equips into the Trinkets head/headphones slot (right-click to auto-equip). */
public class HeadphonesItem extends TrinketItem {
    /** Roughly every 10s per stack; a stale link only needs to be noticed eventually. */
    private static final int CLEANUP_PERIOD_TICKS = 200;
    public HeadphonesItem(Item.Properties properties) {
        super(properties);
    }
    // Worn headphones self-heal every server tick in HeadphoneServer.previewEditor; this
    // covers the pair sitting unworn in the player's own inventory, which that loop never sees.
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!(level instanceof ServerLevel server) || !(entity instanceof ServerPlayer player)
                || Math.floorMod(server.getGameTime() + player.getId() + slotId, CLEANUP_PERIOD_TICKS) != 0) return;
        HeadphoneLinks.read(stack).ifPresent(link -> {
            if (HeadphoneServer.linkedEditorExists(link, server.getServer())) return;
            HeadphoneLinks.clear(stack);
            player.inventoryMenu.broadcastChanges();
        });
    }
}

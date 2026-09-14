package com.mervyn.groove.music;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import java.util.UUID;

final class HeadphoneLinkTests {
    static void run() {
        // A vanilla item exercises component persistence without registering mod items
        // in the executable test harness. The server separately gates the item type.
        var first = new ItemStack(Items.LEATHER_HELMET);
        var second = new ItemStack(Items.LEATHER_HELMET);
        var unrelated = new CompoundTag(); unrelated.putString("other:data", "keep");
        first.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
        check(HeadphoneLinks.read(first).isEmpty(), "New pair is unbound");
        var pos = new BlockPos.MutableBlockPos(4, 5, 6);
        var session = UUID.randomUUID();
        var link = new HeadphoneLinks.Link(ResourceLocation.parse("minecraft:overworld"), pos, session);
        pos.set(7, 8, 9);
        check(link.pos().equals(new BlockPos(4, 5, 6)), "Link owns an immutable position");
        HeadphoneLinks.bind(first, link);
        check(HeadphoneLinks.read(first).orElseThrow().equals(link), "Item stores dimension, position and session");
        check(HeadphoneLinks.read(second).isEmpty(), "Binding one pair does not bind another");
        check(first.get(DataComponents.CUSTOM_DATA).copyTag().getString("other:data").equals("keep"), "Binding preserves unrelated item data");
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var restored = ItemStack.parse(registries, first.save(registries)).orElseThrow();
        check(HeadphoneLinks.read(restored).orElseThrow().equals(link), "Inventory save/reload preserves link");
        var other = new HeadphoneLinks.Link(ResourceLocation.parse("minecraft:the_nether"), link.pos(), UUID.randomUUID());
        HeadphoneLinks.bind(first, other);
        check(HeadphoneLinks.read(first).orElseThrow().equals(other), "Rebinding replaces the link exclusively");
        check(HeadphoneLinks.read(restored).orElseThrow().equals(link), "Copied/transferred stack has independent link data");
        var malformed = new CompoundTag(); var badLink = new CompoundTag();
        badLink.putString("Dimension", "not a dimension"); malformed.put("groove:editor_link", badLink);
        second.set(DataComponents.CUSTOM_DATA, CustomData.of(malformed));
        check(HeadphoneLinks.read(second).isEmpty(), "Malformed link is treated as unbound");
        var request = new HeadphonePackets.Bind(link.pos(), UUID.randomUUID());
        var response = new HeadphonePackets.State(request.request(), false, "Editor is out of reach", true, link.pos());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            HeadphonePackets.Bind.CODEC.encode(wire, request);
            check(HeadphonePackets.Bind.CODEC.decode(wire).equals(request), "Bind packet round trip");
            HeadphonePackets.State.CODEC.encode(wire, response);
            check(HeadphonePackets.State.CODEC.decode(wire).equals(response), "Rejected bind preserves existing link in acknowledgement");
        } finally { wire.release(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

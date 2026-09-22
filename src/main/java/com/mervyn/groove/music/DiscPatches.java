package com.mervyn.groove.music;

import groove.engine.Graph;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.Optional;

/** A burned disc's patch, stored on the item so it travels through inventories, chests and trades. */
public final class DiscPatches {
    private static final String KEY = "groove:patch";
    public record Patch(String graph, double bpm, int nodes) {}

    public static Optional<Patch> read(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag patch = data.getCompound(KEY);
        if (!patch.contains("Graph", net.minecraft.nbt.Tag.TAG_STRING)) return Optional.empty();
        return Optional.of(new Patch(patch.getString("Graph"), patch.getDouble("Bpm"), patch.getInt("Nodes")));
    }

    /** Birth stamps belong to the server clock that assigned them, so they are left off. */
    public static void write(ItemStack stack, Graph graph, double bpm) {
        Graph clean = new Graph(graph.version(), graph.nodes().stream()
                .map(n -> new Graph.Node(n.id(), n.type(), n.params(), n.sample(), null)).toList(), graph.edges());
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag patch = new CompoundTag();
        patch.putString("Graph", GraphJson.encode(clean));
        patch.putDouble("Bpm", bpm);
        patch.putInt("Nodes", clean.nodes().size());
        data.put(KEY, patch);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    /** A playable graph, or empty for a disc whose data was edited or comes from an incompatible version. */
    public static Optional<Graph> graph(Patch patch) {
        if (!Double.isFinite(patch.bpm()) || patch.bpm() < 30 || patch.bpm() > 300) return Optional.empty();
        try {
            return Optional.of(GraphJson.decodeSaved(patch.graph()).graph());
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private DiscPatches() {}
}

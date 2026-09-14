package com.mervyn.groove.block;

import com.mervyn.groove.music.EditorProject;
import com.mervyn.groove.music.EditorSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** Session data lives with the block and is saved with its chunk. */
public final class EditorBlockEntity extends BlockEntity {
    private final EditorProject project = new EditorProject();
    public EditorBlockEntity(BlockPos pos, BlockState state) { super(GrooveBlockEntities.EDITOR, pos, state); }
    public UUID sessionId() { return project.sessionId(); }
    public EditorSession session() { return project.session(); }
    public UUID owner() { return project.owner(); }
    public java.util.Set<UUID> editors() { return project.editors(); }
    public boolean hasOwner() { return project.hasOwner(); }
    public void setOwner(UUID player) { project.setOwner(player); setChanged(); }
    public boolean canEdit(UUID player) { return project.canEdit(player); }
    public void allowEditor(UUID actor, UUID player, boolean allowed) { project.allowEditor(actor, player, allowed); setChanged(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        project.save(tag);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        project.load(tag);
    }
}

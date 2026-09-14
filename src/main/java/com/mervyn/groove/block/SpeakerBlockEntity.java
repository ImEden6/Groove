package com.mervyn.groove.block;

import com.mervyn.groove.music.SpeakerLinks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/**
 * Persistent identity for a speaker segment. Client audio promotes only the
 * lowest segment of each contiguous tower into an audible source. A base
 * segment may also be linked to one editor block, whose committed patch it
 * plays instead of the retired global session (see EDITOR-BLOCK-DESIGN.md).
 */
public final class SpeakerBlockEntity extends BlockEntity {
    private BlockPos editorPos;
    private UUID editorSession;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(GrooveBlockEntities.SPEAKER, pos, state);
    }

    public BlockPos editorPos() { return editorPos; }
    public UUID editorSession() { return editorSession; }
    public boolean linked() { return editorPos != null; }
    public boolean linkedTo(BlockPos pos, UUID session) { return pos.equals(editorPos) && session.equals(editorSession); }

    public void bind(BlockPos pos, UUID session) {
        editorPos = pos.immutable(); editorSession = session; setChanged();
    }

    public void unlink() {
        editorPos = null; editorSession = null; setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (editorPos != null) SpeakerLinks.write(tag, new SpeakerLinks.Link(editorPos, editorSession));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        var link = SpeakerLinks.read(tag);
        editorPos = link.map(SpeakerLinks.Link::pos).orElse(null);
        editorSession = link.map(SpeakerLinks.Link::session).orElse(null);
    }
}

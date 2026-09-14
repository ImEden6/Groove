package com.mervyn.groove.music;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The editor a speaker plays, stored with the speaker's own block entity data. */
public final class SpeakerLinks {
    private static final String POS_KEY = "EditorPos", SESSION_KEY = "EditorSession";

    public record Link(BlockPos pos, UUID session) {
        public Link {
            pos = pos.immutable();
            Objects.requireNonNull(session);
        }
    }

    public static Optional<Link> read(CompoundTag tag) {
        if (!tag.contains(POS_KEY, net.minecraft.nbt.Tag.TAG_LONG) || !tag.hasUUID(SESSION_KEY)) return Optional.empty();
        return Optional.of(new Link(BlockPos.of(tag.getLong(POS_KEY)), tag.getUUID(SESSION_KEY)));
    }

    public static void write(CompoundTag tag, Link link) {
        tag.putLong(POS_KEY, link.pos().asLong());
        tag.putUUID(SESSION_KEY, link.session());
    }

    public static void clear(CompoundTag tag) {
        tag.remove(POS_KEY); tag.remove(SESSION_KEY);
    }

    private SpeakerLinks() {}
}

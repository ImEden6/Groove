package com.mervyn.groove.block;

import net.minecraft.util.StringRepresentable;

/** Matches blockstates/speaker.json's "part" values exactly. */
public enum SpeakerPart implements StringRepresentable {
    BASE("base"), BODY("body");

    private final String name;

    SpeakerPart(String name) { this.name = name; }

    @Override public String getSerializedName() { return name; }
}

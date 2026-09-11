package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import java.util.concurrent.CompletableFuture;

/** Non-positional monitor; future speaker emitters can supply mono streams separately. */
public final class GrooveSound extends AbstractSoundInstance {
    private final AudioStream stream;
    public GrooveSound(AudioStream stream) {
        super(GrooveMod.id("session"), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.stream = stream;
        relative = true;
        attenuation = Attenuation.NONE;
        volume = .7f;
    }
    public GrooveSound(AudioStream stream, BlockPos pos, int height) {
        super(GrooveMod.id("session"), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.stream = stream;
        x = pos.getX() + .5;
        y = pos.getY() + Math.max(.5, height * .5);
        z = pos.getZ() + .5;
        attenuation = Attenuation.LINEAR;
        volume = Math.min(1.0f, .45f + height * .1f);
    }
    @Override public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id, boolean repeat) {
        return CompletableFuture.completedFuture(stream);
    }
    @Override public boolean canStartSilent() { return true; }
}

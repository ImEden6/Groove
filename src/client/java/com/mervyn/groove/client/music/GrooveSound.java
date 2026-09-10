package com.mervyn.groove.client.music;

import com.mervyn.groove.GrooveMod;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
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
    @Override public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id, boolean repeat) {
        return CompletableFuture.completedFuture(stream);
    }
    @Override public boolean canStartSilent() { return true; }
}

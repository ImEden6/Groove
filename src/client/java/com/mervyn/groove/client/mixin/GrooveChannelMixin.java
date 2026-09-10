package com.mervyn.groove.client.mixin;

import com.mervyn.groove.client.music.GrooveAudioStream;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Runs on Minecraft's existing audio executor/context; observes only this stream's source. */
@Mixin(Channel.class)
public abstract class GrooveChannelMixin {
    @Shadow @Final private int source;
    @Shadow private AudioStream stream;
    @Unique private boolean groove$explicitStop;

    @Inject(method = "stop", at = @At("HEAD"))
    private void groove$markExplicitStop(CallbackInfo ci) { groove$explicitStop = true; }

    @Inject(method = "play", at = @At("HEAD"))
    private void groove$markPlay(CallbackInfo ci) { groove$explicitStop = false; }

    // ChannelAccess calls updateStream before stopped()/release(). Queueing fresh
    // buffers alone does not restart a source that drained during a tick stall.
    @Inject(method = "updateStream", at = @At("TAIL"))
    private void groove$recoverUnderrun(CallbackInfo ci) {
        if (!groove$explicitStop && stream instanceof GrooveAudioStream groove && !groove.closed()
                && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED
                && AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) > 0) {
            groove.recoveredUnderrun();
            AL10.alSourcePlay(source);
        }
    }

    @Redirect(method = "pumpBuffers", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/AudioStream;read(I)Ljava/nio/ByteBuffer;"))
    private ByteBuffer groove$readAtPlaybackTime(AudioStream stream, int bytes) throws IOException {
        if (stream instanceof GrooveAudioStream groove) {
            int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
            long lead = Math.max(0L, (long) queued * GrooveAudioStream.CHUNK_FRAMES - offset);
            return groove.readQueued(bytes, lead);
        }
        return stream.read(bytes);
    }
}

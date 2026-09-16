package groove.engine;

import groove.engine.samples.*;
import java.nio.file.Path;
import java.util.Map;

/** Offline sample-slicing demo using only the bundled procedural kit. */
public final class SampleDemo {
    public static void main(String[] args) throws Exception {
        AssetRef kick=FactorySamples.ref("factory:basic/kick.wav");
        AssetRef snare=FactorySamples.ref("factory:basic/snare.wav");
        AssetRef hat=FactorySamples.ref("factory:basic/hat.wav");
        Map<AssetRef,SampleData> bank=Map.of(
                kick,WavDecoder.decode(FactorySamples.bytes(kick.assetId())),
                snare,WavDecoder.decode(FactorySamples.bytes(snare.assetId())),
                hat,WavDecoder.decode(FactorySamples.bytes(hat.assetId())));
        Pattern k=Pattern.sample(new SampleVoice(kick,1,.7,0)).slice(2,0,false);
        Pattern s=Pattern.sample(new SampleVoice(snare,1,.5,.25)).slice(2,0,false);
        Pattern r=Pattern.sample(new SampleVoice(snare,.8,.4,-.25)).slice(2,0,true);
        Pattern h=Pattern.sample(new SampleVoice(hat,1,.35,.35));
        Pattern drumLoop=Pattern.polymeter(8,k,h,s,h,k,h,r,h).swing(16, 0.6);
        Pattern drums=Pattern.alternate(drumLoop, drumLoop, drumLoop, drumLoop.reverse());
        Tone pulseBass=new Tone(Tone.Wave.PULSE,Pitch.hz("C2"),.25,0,1600,2.5,0.35);
        Pattern bass=Pattern.tone(pulseBass).euclid(8,3,0).swing(16, 0.6);
        Tone pulseLead=new Tone(Tone.Wave.PULSE,Pitch.hz("C4"),.12,.2,2400,1.8,0.2);
        Pattern lead=Pattern.tone(pulseLead).scaleSequence(Pitch.midi("C4"),Pitch.Scale.MINOR_PENTATONIC,8,
                new int[]{0,2,3,4,3,2,0,-1}).swing(16, 0.6);
        Score score=Score.compile(Pattern.stack(drums,bass,lead),new Transport(48000,120,4),8,bank);
        Demo.write(Path.of(args.length==0?"sample-demo.wav":args[0]),score);
    }
}

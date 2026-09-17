package groove.engine;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline end-to-end smoke demo. No Minecraft, audio device, or external dependencies. */
public final class Demo {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args.length == 0 ? "demo.wav" : args[0]);
        Transport transport = new Transport(48000, 128, 4);
        Pattern pattern = Pattern.stack(
                Pattern.tone(new Tone(Tone.Wave.SINE, 65.406, .55, 0, 20000)).euclid(8, 4, 0),
                Pattern.tone(new Tone(Tone.Wave.SAW, 261.626, .14, -.55, 900)).euclid(16, 5, 0),
                Pattern.tone(new Tone(Tone.Wave.SINE, 391.995, .23, .55, 20000)).euclid(16, 7, 2));
        Score score = Score.compile(pattern, transport, 4);
        write(path, score);
    }
    static void write(Path path, Score score) throws java.io.IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Renderer renderer = new Renderer(score, 32);
        float[] buffer = new float[1024];
        int bytes = Math.toIntExact(score.frames() * 4);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeBytes("RIFF"); le32(out, 36 + bytes); out.writeBytes("WAVEfmt ");
            le32(out, 16); le16(out, 1); le16(out, 2); le32(out, score.sampleRate());
            le32(out, score.sampleRate() * 4); le16(out, 4); le16(out, 16);
            out.writeBytes("data"); le32(out, bytes);
            while (renderer.position() < score.frames()) {
                int frames = (int) Math.min(512, score.frames() - renderer.position());
                renderer.render(buffer, 0, frames);
                for (int i = 0; i < frames * 2; i++) le16(out, Math.round(buffer[i] * 32767));
            }
        }
        System.out.println("Rendered " + score.size() + " notes to " + path.toAbsolutePath());
    }
    /**
     * Plays each score into the signal graph's audio_render node of the same id, and keeps processing silence
     * after the scores end so effects ring out. Output is scaled down to -1 dBFS only if it would exceed that.
     */
    static float[] render(java.util.Map<String, Score> scores, Graph graph, SessionState state, int totalFrames) {
        SignalGraph signals = GraphCompiler.compile(graph).signals();
        if (signals.sourceCount() != scores.size() || signals.triggerCount() != 0)
            throw new IllegalArgumentException("Each audio_render source needs exactly one score, and no trigger sources");
        SignalRuntime runtime = signals.runtime(state);
        int sources = signals.sourceCount();
        Renderer[] renderers = new Renderer[sources];
        for (int source = 0; source < sources; source++) {
            Score score = scores.get(signals.sourceNodeId(source));
            if (score == null) throw new IllegalArgumentException("No score for source " + signals.sourceNodeId(source));
            renderers[source] = new Renderer(score, 32);
        }
        float[][] buffers = new float[sources][1024];
        double[][] frame = new double[sources][2];
        double[] stereo = new double[2];
        float[] mixed = new float[totalFrames * 2];
        double peak = 0;
        for (int at = 0; at < totalFrames; at += 512) {
            int frames = Math.min(512, totalFrames - at);
            for (int source = 0; source < sources; source++) renderers[source].render(buffers[source], 0, frames);
            for (int f = 0; f < frames; f++) {
                for (int source = 0; source < sources; source++) {
                    frame[source][0] = buffers[source][f * 2];
                    frame[source][1] = buffers[source][f * 2 + 1];
                }
                runtime.process(frame, stereo, Math.round((at + f) * 1e9 / LiveRenderer.SAMPLE_RATE));
                mixed[(at + f) * 2] = (float) stereo[0];
                mixed[(at + f) * 2 + 1] = (float) stereo[1];
                peak = Math.max(peak, Math.max(Math.abs(stereo[0]), Math.abs(stereo[1])));
            }
        }
        double limit = Math.pow(10, -1.0 / 20);
        if (peak > limit) for (int i = 0; i < mixed.length; i++) mixed[i] *= (float) (limit / peak);
        return mixed;
    }

    static void writeWav(Path path, float[] interleaved, int sampleRate) throws java.io.IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        int bytes = interleaved.length * 2;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeBytes("RIFF"); le32(out, 36 + bytes); out.writeBytes("WAVEfmt ");
            le32(out, 16); le16(out, 1); le16(out, 2); le32(out, sampleRate);
            le32(out, sampleRate * 4); le16(out, 4); le16(out, 16);
            out.writeBytes("data"); le32(out, bytes);
            for (float value : interleaved) le16(out, Math.round(Math.max(-1f, Math.min(1f, value)) * 32767));
        }
        System.out.println("Rendered " + interleaved.length / 2 + " frames to " + path.toAbsolutePath());
    }
    static void le16(DataOutputStream out, int n) throws java.io.IOException {
        out.writeByte(n); out.writeByte(n >>> 8);
    }
    static void le32(DataOutputStream out, int n) throws java.io.IOException {
        le16(out, n); le16(out, n >>> 16);
    }
}

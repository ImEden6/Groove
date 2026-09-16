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
    static void write(Path path, LiveRenderer renderer, int sampleRate, int totalFrames) throws java.io.IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        float[] buffer = new float[1024];
        int bytes = totalFrames * 4;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeBytes("RIFF"); le32(out, 36 + bytes); out.writeBytes("WAVEfmt ");
            le32(out, 16); le16(out, 1); le16(out, 2); le32(out, sampleRate);
            le32(out, sampleRate * 4); le16(out, 4); le16(out, 16);
            out.writeBytes("data"); le32(out, bytes);
            int at = 0;
            while (at < totalFrames) {
                int frames = Math.min(512, totalFrames - at);
                renderer.render(buffer, frames, Math.round(at * 1e9 / sampleRate));
                for (int i = 0; i < frames * 2; i++) le16(out, (int) Math.round(buffer[i] * 32767));
                at += frames;
            }
        }
        System.out.println("Rendered " + totalFrames + " frames to " + path.toAbsolutePath());
    }
    static void write(Path path, Score rhythm, Score lead, SignalRuntime delay) throws java.io.IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Renderer rhythmRenderer = new Renderer(rhythm, 32);
        Renderer leadRenderer = new Renderer(lead, 16);
        float[] rhythmBuffer = new float[1024];
        float[] leadBuffer = new float[1024];
        double[] stereo = new double[2];
        int sampleRate = rhythm.sampleRate();
        long totalFrames = Math.max(rhythm.frames(), lead.frames());
        int bytes = Math.toIntExact(totalFrames * 4);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeBytes("RIFF"); le32(out, 36 + bytes); out.writeBytes("WAVEfmt ");
            le32(out, 16); le16(out, 1); le16(out, 2); le32(out, sampleRate);
            le32(out, sampleRate * 4); le16(out, 4); le16(out, 16);
            out.writeBytes("data"); le32(out, bytes);
            long at = 0;
            while (at < totalFrames) {
                int frames = (int) Math.min(512, totalFrames - at);
                rhythmRenderer.render(rhythmBuffer, 0, frames);
                leadRenderer.render(leadBuffer, 0, frames);
                for (int i = 0; i < frames; i++) {
                    stereo[0] = leadBuffer[i * 2];
                    stereo[1] = leadBuffer[i * 2 + 1];
                    long nanos = Math.round((at + i) * 1e9 / sampleRate);
                    delay.process(stereo, nanos);
                    double left = rhythmBuffer[i * 2] + stereo[0];
                    double right = rhythmBuffer[i * 2 + 1] + stereo[1];
                    le16(out, (int) Math.round(Math.tanh(left) * 32767));
                    le16(out, (int) Math.round(Math.tanh(right) * 32767));
                }
                at += frames;
            }
        }
        System.out.println("Rendered " + (rhythm.size() + lead.size()) + " notes to " + path.toAbsolutePath());
    }
    static void le16(DataOutputStream out, int n) throws java.io.IOException {
        out.writeByte(n); out.writeByte(n >>> 8);
    }
    static void le32(DataOutputStream out, int n) throws java.io.IOException {
        le16(out, n); le16(out, n >>> 16);
    }
}

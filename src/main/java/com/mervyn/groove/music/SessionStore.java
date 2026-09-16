package com.mervyn.groove.music;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import groove.engine.Graph;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One atomic replacement commits both graph and tempo. Legacy files are read-only. */
final class SessionStore {
    static final String FILE = "groove-session.json";
    private static final int MAX_BYTES = 262144;
    private static final Gson GSON = new Gson();
    record Saved(Graph graph, double bpm) {
        Saved {
            if (!Double.isFinite(bpm) || bpm < 30 || bpm > 300) throw new IllegalArgumentException("Invalid saved BPM");
        }
    }
    private record Envelope(int version, double bpm, String graphJson) {}
    static Saved read(Path root) throws IOException {
        Path file = root.resolve(FILE);
        if (!Files.exists(file)) {
            Graph graph = GraphJson.decode(readBounded(root.resolve("groove-patch.json"), GraphJson.MAX_LENGTH * 4));
            Path tempo = root.resolve("groove-tempo.txt");
            return new Saved(graph, Files.exists(tempo) ? Double.parseDouble(readBounded(tempo, 64).trim()) : 128);
        }
        try (JsonReader reader = new JsonReader(new StringReader(readBounded(file, MAX_BYTES)))) {
            Integer version = null; Double bpm = null; String graph = null;
            var keys = new java.util.HashSet<String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!keys.add(key)) throw new IOException("Duplicate save field");
                switch (key) {
                    case "version" -> version = reader.nextInt();
                    case "bpm" -> bpm = reader.nextDouble();
                    case "graphJson" -> graph = reader.nextString();
                    default -> throw new IOException("Unknown save field: " + key);
                }
            }
            reader.endObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT
                    || version == null || version != 1 || bpm == null || graph == null)
                throw new IOException("Invalid session save");
            return new Saved(GraphJson.decode(graph), bpm);
        }
    }
    private static String readBounded(Path file, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Save exceeds size limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    @FunctionalInterface interface Commit { void move(Path from, Path to) throws IOException; }
    static void write(Path root, Saved saved) throws IOException {
        write(root, saved, (from, to) -> Files.move(from, to,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));
    }
    static void write(Path root, Saved saved, Commit commit) throws IOException {
        Path current = root.resolve(FILE);
        if (Files.exists(current)) {
            boolean corrupt = false;
            try {
                read(root);
            } catch (Exception error) {
                corrupt = true;
            }
            if (corrupt) {
                Path bak = root.resolve(FILE + ".bak");
                if (!Files.exists(bak)) {
                    Files.copy(current, bak);
                    com.mervyn.groove.GrooveMod.LOGGER.warn("Corrupt session store file backed up to {}", bak);
                }
            }
        }
        String graph = GraphJson.encode(saved.graph());
        GraphJson.decode(graph);
        byte[] bytes = GSON.toJson(new Envelope(1, saved.bpm(), graph)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Save exceeds size limit");
        Path temporary = Files.createTempFile(root, "groove-session-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: unsupported filesystems must report failure.
            commit.move(temporary, root.resolve(FILE));
        } finally { Files.deleteIfExists(temporary); }
    }
}

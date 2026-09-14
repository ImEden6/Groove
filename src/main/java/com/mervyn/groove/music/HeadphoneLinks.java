package com.mervyn.groove.music;

import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent player -> editor headphone links. One atomic replace commits the whole table. */
final class HeadphoneLinks {
    static final String FILE = "groove-headphones.json";
    private static final int MAX_BYTES = 65536;
    private static final int MAX_LINKS = 4096;
    private static final Gson GSON = new Gson();

    record Link(BlockPos pos, UUID session) {}
    private record Entry(String player, int x, int y, int z, String session) {}
    private record Envelope(int version, List<Entry> links) {}

    static Map<UUID, Link> read(Path root) throws IOException {
        Path file = root.resolve(FILE);
        if (!Files.exists(file)) return new HashMap<>();
        String json;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Headphone save exceeds size limit");
            json = new String(bytes, StandardCharsets.UTF_8);
        }
        Envelope envelope = GSON.fromJson(json, Envelope.class);
        if (envelope == null || envelope.version() != 1 || envelope.links() == null || envelope.links().size() > MAX_LINKS)
            throw new IOException("Invalid headphone save");
        var links = new HashMap<UUID, Link>();
        for (Entry entry : envelope.links())
            links.put(UUID.fromString(entry.player()), new Link(new BlockPos(entry.x(), entry.y(), entry.z()), UUID.fromString(entry.session())));
        return links;
    }

    static void write(Path root, Map<UUID, Link> links) throws IOException {
        var entries = new ArrayList<Entry>(links.size());
        for (var e : links.entrySet())
            entries.add(new Entry(e.getKey().toString(), e.getValue().pos().getX(), e.getValue().pos().getY(), e.getValue().pos().getZ(), e.getValue().session().toString()));
        byte[] bytes = GSON.toJson(new Envelope(1, entries)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Headphone save exceeds size limit");
        Path temporary = Files.createTempFile(root, "groove-headphones-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, root.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private HeadphoneLinks() {}
}

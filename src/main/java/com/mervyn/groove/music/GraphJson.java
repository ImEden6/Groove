package com.mervyn.groove.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import groove.engine.Graph;
import groove.engine.GraphCompiler;
import groove.engine.NodeType;

import java.io.IOException;

/** Size/depth validation precedes JSON parsing and graph evaluation. */
public final class GraphJson {
    public static final int MAX_LENGTH = 32768;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(NodeType.class, new TypeAdapter<NodeType>() {
                @Override
                public void write(JsonWriter out, NodeType value) throws IOException {
                    if (value == null) { out.nullValue(); return; }
                    out.value(switch (value) {
                        case TONE -> "tone";
                        case GENERATOR_SAMPLE -> "generator/sample";
                        case FAST -> "fast";
                        case EUCLID -> "euclid";
                        case STACK -> "stack";
                        case OUTPUT -> "output";
                    });
                }
                @Override
                public NodeType read(JsonReader in) throws IOException {
                    if (in.peek() == com.google.gson.stream.JsonToken.NULL) { in.nextNull(); return null; }
                    String s = in.nextString();
                    return switch (s) {
                        case "tone" -> NodeType.TONE;
                        case "generator/sample" -> NodeType.GENERATOR_SAMPLE;
                        case "fast" -> NodeType.FAST;
                        case "euclid" -> NodeType.EUCLID;
                        case "stack" -> NodeType.STACK;
                        case "output" -> NodeType.OUTPUT;
                        default -> throw new IOException("Unknown node type: " + s);
                    };
                }
            })
            .setPrettyPrinting().create();
    public static String encode(Graph graph) { return GSON.toJson(graph); }
    public static Graph decode(String json) {
        if (json.length() > MAX_LENGTH) throw new IllegalArgumentException("Patch exceeds 32 KiB characters");
        int depth = 0;
        boolean string = false, escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '[' || c == '{') {
                if (++depth > 16) throw new IllegalArgumentException("JSON nesting too deep");
            } else if (c == ']' || c == '}') depth--;
        }
        try {
            Graph graph = GSON.fromJson(json, Graph.class);
            if (graph == null) throw new IllegalArgumentException("Empty patch");
            GraphCompiler.compile(graph);
            return graph;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid patch: " + error.getMessage(), error);
        }
    }
}

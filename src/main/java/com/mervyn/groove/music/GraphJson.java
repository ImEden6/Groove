package com.mervyn.groove.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import groove.engine.Graph;
import groove.engine.GraphCompiler;

/** Size/depth validation precedes JSON parsing and graph evaluation. */
public final class GraphJson {
    public static final int MAX_LENGTH = 32768;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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

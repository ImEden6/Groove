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
                        case ALTERNATE -> "alternate";
                        case PROBABILITY -> "probability";
                        case POLYMETER -> "polymeter";
                        case OUTPUT -> "output";
                        default -> value.idStem();
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
                        case "alternate" -> NodeType.ALTERNATE;
                        case "probability" -> NodeType.PROBABILITY;
                        case "polymeter" -> NodeType.POLYMETER;
                        case "transpose" -> NodeType.TRANSPOSE;
                        case "scale_sequence" -> NodeType.SCALE_SEQUENCE;
                        case "chord" -> NodeType.CHORD;
                        case "sample_slice" -> NodeType.SAMPLE_SLICE;
                        case "reverse" -> NodeType.REVERSE;
                        case "swing" -> NodeType.SWING;
                        case "output" -> NodeType.OUTPUT;
                        default -> {
                            NodeType match = null;
                            for (NodeType type : NodeType.values()) if (type.isSignalNode() && type.idStem().equals(s)) match = type;
                            if (match == null) throw new IOException("Unknown node type: " + s);
                            yield match;
                        }
                    };
                }
            })
            .setPrettyPrinting().create();
    public static String encode(Graph graph) { return GSON.toJson(graph); }
    /** Explicit canonical import; decode itself remains lossless for stored and wire snapshots. */
    public static Graph decodeCurrent(String json) { return decode(json).toV3(); }
    public static Graph decode(String json) { return decode(json, true); }
    public static Graph decodeDraft(String json) { return decode(json, false); }
    private static Graph decode(String json, boolean compile) {
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
            var tree = com.google.gson.JsonParser.parseString(json);
            // Note names are authoring sugar; stored/wire graphs retain the numeric schema.
            if (tree.isJsonObject() && tree.getAsJsonObject().has("nodes")) {
                for (var element : tree.getAsJsonObject().getAsJsonArray("nodes")) {
                    var node = element.getAsJsonObject();
                    if (!node.has("params") || !node.get("params").isJsonObject()) continue;
                    var params = node.getAsJsonObject("params");
                    String type = node.has("type") ? node.get("type").getAsString() : "";
                    String key = type.equals("tone") ? groove.engine.NodeParam.FREQUENCY
                            : type.equals("scale_sequence") ? groove.engine.NodeParam.ROOT : null;
                    if (key == null || !params.has(key) || !params.get(key).isJsonPrimitive()) continue;
                    var value = params.getAsJsonPrimitive(key);
                    if (value.isString() && value.getAsString().matches("[A-Ga-g].*")) {
                        String note = value.getAsString();
                        params.addProperty(key, key.equals(groove.engine.NodeParam.ROOT)
                                ? groove.engine.Pitch.midi(note) : groove.engine.Pitch.hz(note));
                    }
                }
            }
            Graph graph = GSON.fromJson(tree, Graph.class);
            if (graph == null) throw new IllegalArgumentException("Empty patch");
            if (compile) GraphCompiler.compile(graph);
            else {
                if (graph.version() < 1 || graph.version() > Graph.CURRENT_VERSION || graph.nodes().size() > 64 || graph.edges().size() > 128) throw new IllegalArgumentException("Invalid draft bounds");
                var ids = new java.util.HashSet<String>();
                for (var node : graph.nodes()) {
                    if (node.id() == null || !node.id().matches("[a-zA-Z0-9_-]{1,32}") || node.type() == null || !ids.add(node.id())) throw new IllegalArgumentException("Invalid draft node");
                    int maxParams = node.type().isSignalNode() ? 16 : node.type() == NodeType.SCALE_SEQUENCE ? 12 : 8;
                    if (node.params().size() > maxParams || node.params().values().stream().anyMatch(v -> !Double.isFinite(v))) throw new IllegalArgumentException("Invalid draft parameters");
                }
                for (var edge : graph.edges()) if (!ids.contains(edge.fromNode()) || !ids.contains(edge.toNode()) || edge.fromPort() == null || edge.toPort() == null) throw new IllegalArgumentException("Invalid draft edge");
            }
            return graph;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid patch: " + error.getMessage(), error);
        }
    }
}

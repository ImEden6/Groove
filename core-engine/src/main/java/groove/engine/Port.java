package groove.engine;

import java.util.Objects;

/** Named node socket. Input limits count edges, including distinct sources of equal patterns. */
public record Port(String name, PortType type, int minConnections, int maxConnections) {
    public Port {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        if (name.isBlank() || minConnections < 0 || maxConnections < minConnections)
            throw new IllegalArgumentException("Invalid port declaration");
    }

    public boolean accepts(Port source) { return source != null && type == source.type; }

    /** Shared by GraphCompiler and SignalGraph: every input port's bound edge count must fall
     *  within its declared min/max, including ports with zero required connections. */
    static void validateArity(java.util.Collection<Graph.Node> nodes, java.util.List<Graph.Edge> edges) {
        for (Graph.Node node : nodes) {
            for (Port port : node.type().inputPorts()) {
                long count = edges.stream().filter(e -> e.toNode().equals(node.id()) && e.toPort().equals(port.name())).count();
                if (count < port.minConnections() || count > port.maxConnections())
                    throw new IllegalArgumentException("Wrong input count on " + node.id() + "." + port.name());
            }
        }
    }
}

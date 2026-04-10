package edu.metalscenic.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Waypoints + road segments from a TMG. Undirected; each segment is one Edge.
 */
public final class HighwayGraph {

    private final List<Vertex> vertices;
    private final List<Edge> edges;
    private final List<List<Integer>> adjacencyByEdgeIndex;

    public HighwayGraph(List<Vertex> vertices, List<Edge> edges) {
        this.vertices = List.copyOf(vertices);
        this.edges = List.copyOf(edges);
        this.adjacencyByEdgeIndex = buildAdjacency(vertices.size(), edges);
    }

    private static List<List<Integer>> buildAdjacency(int n, List<Edge> edges) {
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int ei = 0; ei < edges.size(); ei++) {
            Edge e = edges.get(ei);
            adj.get(e.endpointA()).add(ei);
            adj.get(e.endpointB()).add(ei);
        }
        for (int i = 0; i < n; i++) {
            adj.set(i, Collections.unmodifiableList(adj.get(i)));
        }
        return Collections.unmodifiableList(adj);
    }

    public int vertexCount() {
        return vertices.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Vertex vertex(int index) {
        return vertices.get(index);
    }

    public List<Vertex> vertices() {
        return vertices;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Integer> incidentEdges(int v) {
        return adjacencyByEdgeIndex.get(v);
    }
}

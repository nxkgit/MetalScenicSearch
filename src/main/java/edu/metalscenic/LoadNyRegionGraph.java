package edu.metalscenic;

import edu.metalscenic.graph.Edge;
import edu.metalscenic.graph.HighwayGraph;
import edu.metalscenic.graph.TmgParser;
import edu.metalscenic.graph.Vertex;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads NY-region.tmg (or args[0]) and prints a few stats.
 */
public final class LoadNyRegionGraph {

    public static void main(String[] args) throws IOException {
        Path path = args.length > 0 ? Path.of(args[0]) : Path.of("NY-region.tmg");

        HighwayGraph g = TmgParser.parse(path);

        System.out.println("Loaded: " + path.toAbsolutePath());
        System.out.println("Vertices: " + g.vertexCount());
        System.out.println("Edges:    " + g.edgeCount());

        int maxDeg = 0;
        int sumDeg = 0;
        for (int v = 0; v < g.vertexCount(); v++) {
            int d = g.incidentEdges(v).size();
            sumDeg += d;
            maxDeg = Math.max(maxDeg, d);
        }
        System.out.println("Max degree:     " + maxDeg);
        System.out.println("Avg degree:     " + (2.0 * g.edgeCount() / g.vertexCount()));

        if (sumDeg != 2 * g.edgeCount()) {
            throw new IllegalStateException("degree sum != 2|E|");
        }

        Vertex sample = g.vertex(0);
        System.out.println();
        System.out.println("Sample vertex[0]: " + sample.label());
        System.out.println("  lat " + sample.lat() + ", lon " + sample.lon());

        Edge sampleEdge = g.edges().get(0);
        System.out.println();
        System.out.println("Sample edge[0]: " + sampleEdge.endpointA() + " - " + sampleEdge.endpointB());
        System.out.println("  " + sampleEdge.roadLabel());
        System.out.println("  shaping floats: " + sampleEdge.shapingPoints().length);
    }
}

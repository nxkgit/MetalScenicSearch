import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the longest simple route between two vertices in a highway graph.
 * "Simple" means no edge is reused (but vertices may be revisited).
 * Uses DFS backtracking — correct but exponential; suitable for small graphs.
 */
public final class ScenicSearch {

    private final HighwayGraph graph;
    private final double[] edgeWeightKm;

    private boolean[] usedEdge;
    private double bestDist;
    private List<Integer> bestPath;
    private List<Integer> currentPath;

    public ScenicSearch(HighwayGraph graph) {
        this.graph = graph;
        this.edgeWeightKm = computeWeights(graph);
    }

    private static double[] computeWeights(HighwayGraph g) {
        double[] w = new double[g.edgeCount()];
        for (int i = 0; i < g.edgeCount(); i++) {
            Edge e = g.edges().get(i);
            Vertex a = g.vertex(e.endpointA());
            Vertex b = g.vertex(e.endpointB());
            w[i] = haversineKm(a.lat(), a.lon(), b.lat(), b.lon());
        }
        return w;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public Result search(int start, int end) {
        usedEdge = new boolean[graph.edgeCount()];
        bestDist = -1.0;
        bestPath = List.of();
        currentPath = new ArrayList<>();
        dfs(start, end, 0.0);
        return new Result(bestDist, bestPath);
    }

    private void dfs(int current, int target, double dist) {
        if (current == target && dist > bestDist) {
            bestDist = dist;
            bestPath = List.copyOf(currentPath);
        }
        for (int ei : graph.incidentEdges(current)) {
            if (!usedEdge[ei]) {
                int next = graph.edges().get(ei).otherEndpoint(current);
                usedEdge[ei] = true;
                currentPath.add(ei);
                dfs(next, target, dist + edgeWeightKm[ei]);
                currentPath.remove(currentPath.size() - 1);
                usedEdge[ei] = false;
            }
        }
    }

    public record Result(double distanceKm, List<Integer> edgeIndices) {
        public boolean found() { return distanceKm >= 0; }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: ScenicSearch <file.tmg> <startIndex> <endIndex>");
            System.exit(1);
        }
        HighwayGraph g = TmgParser.parse(Path.of(args[0]));
        int start = Integer.parseInt(args[1]);
        int end   = Integer.parseInt(args[2]);

        System.out.println("Loaded: " + g.vertexCount() + " vertices, " + g.edgeCount() + " edges");
        System.out.println("Start: [" + start + "] " + g.vertex(start).label());
        System.out.println("End:   [" + end   + "] " + g.vertex(end).label());
        System.out.println("Searching...");

        long t0 = System.currentTimeMillis();
        Result result = new ScenicSearch(g).search(start, end);
        long elapsed = System.currentTimeMillis() - t0;

        if (!result.found()) {
            System.out.println("No path found.");
        } else {
            System.out.printf("Longest path: %.2f km over %d edges (%d ms)%n",
                    result.distanceKm(), result.edgeIndices().size(), elapsed);
        }
    }
}

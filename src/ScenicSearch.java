import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Finds the longest simple route between two vertices in a highway graph.
 * "Simple" means no edge is reused (but vertices may be revisited).
 *
 * Pruning strategies:
 *  1. Reachability check   – prune branches where target becomes unreachable.
 *  2. Upper-bound pruning  – skip branches that can't beat bestDist even using
 *                            all remaining unused edges.
 *  3. Dead-end detection   – prune when target has degree 0 (and ≠ current).
 *  4. Edge ordering        – try heavier edges first to find good solutions
 *                            early, tightening the upper-bound pruning sooner.
 *  5. Time budget          – return best-so-far if wall time exceeds limit.
 *  6. canReach pooling     – reuse visited[] array across calls to avoid GC.
 */
public final class ScenicSearch {

    // ── tuneable constants ────────────────────────────────────────────────────

    /** Print a progress line every this many DFS calls. 0 = silent. */
    private static final long LOG_INTERVAL = 2_000_000L;

    /** Stop after this many milliseconds and return best found so far.
     *  Set to Long.MAX_VALUE for exact (unbounded) search. */
    private static final long TIME_LIMIT_MS = 30_000L;

    // ── graph data ────────────────────────────────────────────────────────────

    private final HighwayGraph graph;
    private final double[]     edgeWeightKm;

    /**
     * Incident edges pre-sorted heaviest-first per vertex.
     * Trying the longest edges first finds good solutions early,
     * which tightens upper-bound pruning for everything that follows.
     */
    private final int[][] sortedIncident;

    // ── per-search mutable state ──────────────────────────────────────────────

    private boolean[]     usedEdge;
    private int[]         unusedDegree;  // live unused-degree per vertex
    private double        totalUnused;   // sum of all unused edge weights
    private double        bestDist;
    private List<Integer> bestPath;
    private List<Integer> currentPath;

    // progress / time-budget
    private long callCount;
    private long deadline;        // System.currentTimeMillis() deadline
    private boolean timeLimitHit;

    // pooled visited array for canReach — avoids allocating per BFS call
    private boolean[] visitedPool;
    private int[]     visitedStamp;  // stamp-based clearing: O(1) reset
    private int       currentStamp;

    // ── constructor ───────────────────────────────────────────────────────────

    public ScenicSearch(HighwayGraph graph) {
        this.graph        = graph;
        this.edgeWeightKm = computeWeights(graph);
        this.sortedIncident = buildSortedIncident(graph, edgeWeightKm);

        int V = graph.vertexCount();
        this.visitedStamp = new int[V];
        this.currentStamp = 0;
    }

    // ── weight calculation ────────────────────────────────────────────────────

    private static double[] computeWeights(HighwayGraph g) {
        double[] w = new double[g.edgeCount()];
        for (int i = 0; i < g.edgeCount(); i++) {
            Edge   e = g.edges().get(i);
            Vertex a = g.vertex(e.endpointA());
            Vertex b = g.vertex(e.endpointB());
            w[i] = haversineKm(a.lat(), a.lon(), b.lat(), b.lon());
        }
        return w;
    }

    private static double haversineKm(double lat1, double lon1,
                                       double lat2, double lon2) {
        double R    = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * For each vertex, build an array of its incident edge indices sorted
     * by edge weight descending (heaviest first).
     */
    private static int[][] buildSortedIncident(HighwayGraph g, double[] w) {
        int V = g.vertexCount();
        int[][] sorted = new int[V][];
        for (int v = 0; v < V; v++) {
            List<Integer> inc = g.incidentEdges(v);
            Integer[] boxed = inc.toArray(new Integer[0]);
            Arrays.sort(boxed, (a, b) -> Double.compare(w[b], w[a]));
            sorted[v] = new int[boxed.length];
            for (int i = 0; i < boxed.length; i++) sorted[v][i] = boxed[i];
        }
        return sorted;
    }

    // ── public API ────────────────────────────────────────────────────────────

    public Result search(int start, int end) {
        int E = graph.edgeCount();
        int V = graph.vertexCount();

        usedEdge     = new boolean[E];
        unusedDegree = new int[V];
        totalUnused  = 0.0;

        for (int ei = 0; ei < E; ei++) {
            Edge e = graph.edges().get(ei);
            unusedDegree[e.endpointA()]++;
            unusedDegree[e.endpointB()]++;
            totalUnused += edgeWeightKm[ei];
        }

        bestDist     = -1.0;
        bestPath     = List.of();
        currentPath  = new ArrayList<>();
        callCount    = 0;
        timeLimitHit = false;
        currentStamp = 0;
        deadline     = System.currentTimeMillis() + TIME_LIMIT_MS;

        dfs(start, end, 0.0);
        return new Result(bestDist, bestPath, timeLimitHit);
    }

    // ── core DFS ──────────────────────────────────────────────────────────────

    private void dfs(int current, int target, double dist) {

        // ── time budget ──────────────────────────────────────────────────────
        if ((++callCount & 0xFFFF) == 0) {  // check every 65536 calls
            if (System.currentTimeMillis() >= deadline) {
                timeLimitHit = true;
            }
            if (LOG_INTERVAL > 0 && (callCount % LOG_INTERVAL) == 0) {
                System.out.printf("  calls=%,d  best=%.1f km  depth=%d%n",
                        callCount, bestDist, currentPath.size());
            }
        }
        if (timeLimitHit) return;

        // ── record solution ──────────────────────────────────────────────────
        if (current == target && dist > bestDist) {
            bestDist = dist;
            bestPath = List.copyOf(currentPath);
        }

        // ── upper-bound pruning ──────────────────────────────────────────────
        if (dist + totalUnused <= bestDist) return;

        // ── dead-end detection ───────────────────────────────────────────────
        if (current != target && unusedDegree[target] == 0) return;

        // ── explore neighbours (heaviest edge first) ─────────────────────────
        for (int ei : sortedIncident[current]) {
            if (usedEdge[ei]) continue;

            int    next = graph.edges().get(ei).otherEndpoint(current);
            double w    = edgeWeightKm[ei];

            if (next != target && !canReach(next, target, ei)) continue;

            useEdge(ei);
            currentPath.add(ei);
            dfs(next, target, dist + w);
            currentPath.remove(currentPath.size() - 1);
            unuseEdge(ei);

            if (timeLimitHit) return;
        }
    }

    // ── edge bookkeeping ──────────────────────────────────────────────────────

    private void useEdge(int ei) {
        usedEdge[ei] = true;
        totalUnused -= edgeWeightKm[ei];
        Edge e = graph.edges().get(ei);
        unusedDegree[e.endpointA()]--;
        unusedDegree[e.endpointB()]--;
    }

    private void unuseEdge(int ei) {
        usedEdge[ei] = false;
        totalUnused += edgeWeightKm[ei];
        Edge e = graph.edges().get(ei);
        unusedDegree[e.endpointA()]++;
        unusedDegree[e.endpointB()]++;
    }

    // ── reachability check ────────────────────────────────────────────────────
    // Uses stamp-based visited tracking so we never allocate a fresh array.
    // Incrementing currentStamp is equivalent to clearing visited[].

    private boolean canReach(int from, int target, int excludeEdge) {
        if (from == target) return true;

        // advance stamp — any cell != currentStamp counts as unvisited
        int stamp = ++currentStamp;

        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(from);
        visitedStamp[from] = stamp;

        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (int ei : graph.incidentEdges(v)) {
                if (usedEdge[ei] || ei == excludeEdge) continue;
                int nb = graph.edges().get(ei).otherEndpoint(v);
                if (nb == target) return true;
                if (visitedStamp[nb] != stamp) {
                    visitedStamp[nb] = stamp;
                    stack.push(nb);
                }
            }
        }
        return false;
    }

    // ── result type ───────────────────────────────────────────────────────────

    public record Result(double distanceKm, List<Integer> edgeIndices,
                         boolean approximate) {
        public boolean found()       { return distanceKm >= 0; }
        public boolean isOptimal()   { return found() && !approximate; }
    }

    // ── main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: ScenicSearch <file.tmg> <startIndex> <endIndex>");
            System.exit(1);
        }
        HighwayGraph g     = TmgParser.parse(Path.of(args[0]));
        int          start = Integer.parseInt(args[1]);
        int          end   = Integer.parseInt(args[2]);

        System.out.println("Loaded: " + g.vertexCount() + " vertices, "
                + g.edgeCount() + " edges");
        System.out.println("Start: [" + start + "] " + g.vertex(start).label());
        System.out.println("End:   [" + end   + "] " + g.vertex(end).label());
        System.out.printf ("Time limit: %d s%n", TIME_LIMIT_MS / 1000);
        System.out.println("Searching...");

        long   t0      = System.currentTimeMillis();
        Result result  = new ScenicSearch(g).search(start, end);
        long   elapsed = System.currentTimeMillis() - t0;

        if (!result.found()) {
            System.out.println("No path found.");
        } else {
            System.out.printf("Longest path: %.2f km over %d edges (%d ms) [%s]%n",
                    result.distanceKm(), result.edgeIndices().size(), elapsed,
                    result.isOptimal() ? "optimal" : "best found, may not be optimal");
        }
    }
}
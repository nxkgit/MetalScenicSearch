import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Parallel version of ScenicSearch.
 *
 * Splits the top-level DFS branches across a ForkJoinPool (work-stealing),
 * so all CPU cores stay busy. Each thread runs an independent DFS with its
 * own copy of mutable state; they share only a single AtomicLong for the
 * current best distance so a good solution found by one thread prunes others
 * immediately.
 *
 * Drop-in companion to ScenicSearch.java — same Result shape, same
 * HighwayGraph / Edge / Vertex / TmgParser dependencies.
 *
 * Tuneable constants:
 *   TIME_LIMIT_MS  – wall-clock budget; returns best-so-far when hit.
 *   LOG_INTERVAL   – progress line every N DFS calls per thread (0 = silent).
 *   SPLIT_DEPTH    – DFS levels expanded before handing off to threads.
 *                    1 = one task per root edge (few tasks, less overhead).
 *                    2 = one task per root-edge pair (more tasks, better
 *                    load-balancing). Increase if some threads finish early.
 */
public final class ScenicSearchParallel {

    // ── tuneable constants ────────────────────────────────────────────────────

    private static final long TIME_LIMIT_MS = 30_000L;
    private static final long LOG_INTERVAL  = 2_000_000L;
    private static final int  SPLIT_DEPTH   = 2;

    // ── immutable graph data (shared safely across threads) ───────────────────

    private final HighwayGraph graph;
    private final double[]     edgeWeightKm;

    /** Incident edge lists pre-sorted heaviest-first per vertex. */
    private final int[][] sortedIncident;

    // ── shared search state ───────────────────────────────────────────────────

    /** Best distance found so far, stored as long bits for AtomicLong. */
    private final AtomicLong sharedBestDist =
            new AtomicLong(Double.doubleToLongBits(-1.0));

    /** Best path — updated under pathLock whenever sharedBestDist improves. */
    private volatile List<Integer> sharedBestPath = List.of();
    private final    Object        pathLock        = new Object();

    private volatile boolean timeLimitHit = false;
    private          long    deadline;

    // ── constructor ───────────────────────────────────────────────────────────

    public ScenicSearchParallel(HighwayGraph graph) {
        this.graph          = graph;
        this.edgeWeightKm   = computeWeights(graph);
        this.sortedIncident = buildSortedIncident(graph, edgeWeightKm);
    }

    // ── weight helpers ────────────────────────────────────────────────────────

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

    private static int[][] buildSortedIncident(HighwayGraph g, double[] w) {
        int V = g.vertexCount();
        int[][] sorted = new int[V][];
        for (int v = 0; v < V; v++) {
            List<Integer> inc   = g.incidentEdges(v);
            Integer[]     boxed = inc.toArray(new Integer[0]);
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

        // Reset shared state for this search
        sharedBestDist.set(Double.doubleToLongBits(-1.0));
        sharedBestPath = List.of();
        timeLimitHit   = false;
        deadline       = System.currentTimeMillis() + TIME_LIMIT_MS;

        // Build initial bookkeeping arrays
        boolean[] initUsed   = new boolean[E];
        int[]     initDegree = new int[V];
        double    initTotal  = 0.0;
        for (int ei = 0; ei < E; ei++) {
            Edge e = graph.edges().get(ei);
            initDegree[e.endpointA()]++;
            initDegree[e.endpointB()]++;
            initTotal += edgeWeightKm[ei];
        }

        // Greedy seed: gives every thread a non-trivial lower bound from the
        // start so upper-bound pruning fires on the very first branches.
        greedySeed(start, end, initUsed.clone(), initDegree.clone(), initTotal);

        // Expand the first SPLIT_DEPTH levels of the DFS tree into tasks
        List<SearchState> tasks = new ArrayList<>();
        expandTasks(start, end, 0.0,
                initUsed.clone(), initDegree.clone(), initTotal,
                new ArrayList<>(), tasks, SPLIT_DEPTH);

        System.out.printf("  Dispatching %d tasks across %d cores%n",
                tasks.size(), Runtime.getRuntime().availableProcessors());

        // Work-stealing pool — idle threads steal tasks from busy ones
        ForkJoinPool pool = new ForkJoinPool();
        List<Future<?>> futures = new ArrayList<>();
        for (SearchState state : tasks) {
            futures.add(pool.submit(() -> new SearchWorker(state, end).run()));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();

        double best = Double.longBitsToDouble(sharedBestDist.get());
        return new Result(best, sharedBestPath, timeLimitHit);
    }

    // ── task expansion ────────────────────────────────────────────────────────
    // Walks the DFS tree `depth` levels deep, collecting leaf states as tasks.
    // Each leaf gets its own deep-copied mutable state — no sharing between tasks.

    private void expandTasks(int current, int target, double dist,
                              boolean[] usedEdge, int[] unusedDegree,
                              double totalUnused, List<Integer> path,
                              List<SearchState> out, int depth) {

        if (current == target &&
                dist > Double.longBitsToDouble(sharedBestDist.get()))
            updateSharedBest(dist, path);

        // Leaf — emit as an independent task
        if (depth == 0) {
            out.add(new SearchState(current, dist,
                    usedEdge.clone(), unusedDegree.clone(),
                    totalUnused, new ArrayList<>(path)));
            return;
        }

        // Prune during expansion to avoid generating hopeless tasks
        if (dist + totalUnused <= Double.longBitsToDouble(sharedBestDist.get())) return;
        if (current != target && unusedDegree[target] == 0) return;

        for (int ei : sortedIncident[current]) {
            if (usedEdge[ei]) continue;
            int    next = graph.edges().get(ei).otherEndpoint(current);
            double w    = edgeWeightKm[ei];

            if (next != target && !canReachSimple(next, target, ei, usedEdge)) continue;

            // Commit in-place (undo below)
            usedEdge[ei] = true;
            unusedDegree[graph.edges().get(ei).endpointA()]--;
            unusedDegree[graph.edges().get(ei).endpointB()]--;
            totalUnused -= w;
            path.add(ei);

            expandTasks(next, target, dist + w,
                    usedEdge, unusedDegree, totalUnused, path, out, depth - 1);

            // Undo
            path.remove(path.size() - 1);
            usedEdge[ei] = false;
            unusedDegree[graph.edges().get(ei).endpointA()]++;
            unusedDegree[graph.edges().get(ei).endpointB()]++;
            totalUnused += w;
        }
    }

    // Allocating canReach — used only during task expansion (rare)
    private boolean canReachSimple(int from, int target, int excludeEdge,
                                    boolean[] usedEdge) {
        if (from == target) return true;
        boolean[]      visited = new boolean[graph.vertexCount()];
        Deque<Integer> stack   = new ArrayDeque<>();
        stack.push(from);
        visited[from] = true;
        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (int ei : graph.incidentEdges(v)) {
                if (usedEdge[ei] || ei == excludeEdge) continue;
                int nb = graph.edges().get(ei).otherEndpoint(v);
                if (nb == target) return true;
                if (!visited[nb]) { visited[nb] = true; stack.push(nb); }
            }
        }
        return false;
    }

    // ── greedy seed ───────────────────────────────────────────────────────────

    private void greedySeed(int start, int end,
                             boolean[] used, int[] degree, double totalUnused) {
        List<Integer> path    = new ArrayList<>();
        double        dist    = 0.0;
        int           current = start;
        while (true) {
            if (current == end &&
                    dist > Double.longBitsToDouble(sharedBestDist.get()))
                updateSharedBest(dist, path);

            int bestEdge = -1;
            for (int ei : sortedIncident[current]) {
                if (!used[ei]) { bestEdge = ei; break; }
            }
            if (bestEdge == -1) break;

            used[bestEdge] = true;
            path.add(bestEdge);
            dist    += edgeWeightKm[bestEdge];
            current  = graph.edges().get(bestEdge).otherEndpoint(current);
        }
    }

    // ── shared best update (lock-free distance, locked path) ─────────────────

    private void updateSharedBest(double newDist, List<Integer> newPath) {
        long newBits = Double.doubleToLongBits(newDist);
        long cur;
        do {
            cur = sharedBestDist.get();
            if (Double.longBitsToDouble(cur) >= newDist) return;
        } while (!sharedBestDist.compareAndSet(cur, newBits));
        synchronized (pathLock) {
            sharedBestPath = List.copyOf(newPath);
        }
    }

    // ── search state snapshot passed to each worker ───────────────────────────

    private record SearchState(int vertex, double dist,
                                boolean[] usedEdge, int[] unusedDegree,
                                double totalUnused, List<Integer> path) {}

    // ── per-thread worker ─────────────────────────────────────────────────────

    private class SearchWorker {

        private final int           target;
        private final int           startVertex;
        private final double        startDist;
        private final boolean[]     usedEdge;
        private final int[]         unusedDegree;
        private       double        totalUnused;
        private       double        localBest;
        private final List<Integer> currentPath;
        private       long          callCount;

        // Stamp-based visited array — O(1) reset, zero allocation per BFS call
        private final int[] visitedStamp;
        private       int   stamp;

        SearchWorker(SearchState s, int target) {
            this.target       = target;
            this.startVertex  = s.vertex();
            this.startDist    = s.dist();
            this.usedEdge     = s.usedEdge();
            this.unusedDegree = s.unusedDegree();
            this.totalUnused  = s.totalUnused();
            this.currentPath  = s.path();
            this.localBest    = Double.longBitsToDouble(sharedBestDist.get());
            this.callCount    = 0;
            this.visitedStamp = new int[graph.vertexCount()];
            this.stamp        = 0;
        }

        void run() {
            // Refresh — other threads may have improved bestDist during expansion
            localBest = Double.longBitsToDouble(sharedBestDist.get());
            dfs(startVertex, startDist);
        }

        // ── DFS ───────────────────────────────────────────────────────────────

        private void dfs(int current, double dist) {

            // Time check + progress every 65536 calls (cheap bitwise test)
            if ((++callCount & 0xFFFF) == 0) {
                if (System.currentTimeMillis() >= deadline) timeLimitHit = true;
                double shared = Double.longBitsToDouble(sharedBestDist.get());
                if (shared > localBest) localBest = shared;
                if (LOG_INTERVAL > 0 && (callCount % LOG_INTERVAL) == 0) {
                    System.out.printf("  [%s] calls=%,d  best=%.1f km  depth=%d%n",
                            Thread.currentThread().getName(),
                            callCount, localBest, currentPath.size());
                }
            }
            if (timeLimitHit) return;

            // Record solution
            if (current == target && dist > localBest) {
                localBest = dist;
                updateSharedBest(dist, currentPath);
            }

            // Upper-bound prune
            if (dist + totalUnused <= localBest) return;

            // Dead-end detection
            if (current != target && unusedDegree[target] == 0) return;

            // Explore neighbours heaviest-first
            for (int ei : sortedIncident[current]) {
                if (usedEdge[ei]) continue;

                int    next = graph.edges().get(ei).otherEndpoint(current);
                double w    = edgeWeightKm[ei];

                if (next != target && !canReach(next, target, ei)) continue;

                useEdge(ei);
                currentPath.add(ei);
                dfs(next, dist + w);
                currentPath.remove(currentPath.size() - 1);
                unuseEdge(ei);

                if (timeLimitHit) return;
            }
        }

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

        // Stamp-based canReach — reuses visitedStamp[], no allocation per call
        private boolean canReach(int from, int target, int excludeEdge) {
            if (from == target) return true;
            int            s     = ++stamp;
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(from);
            visitedStamp[from] = s;
            while (!stack.isEmpty()) {
                int v = stack.pop();
                for (int ei : sortedIncident[v]) {
                    if (usedEdge[ei] || ei == excludeEdge) continue;
                    int nb = graph.edges().get(ei).otherEndpoint(v);
                    if (nb == target) return true;
                    if (visitedStamp[nb] != s) {
                        visitedStamp[nb] = s;
                        stack.push(nb);
                    }
                }
            }
            return false;
        }
    }

    // ── result type ───────────────────────────────────────────────────────────

    public record Result(double distanceKm, List<Integer> edgeIndices,
                         boolean approximate) {
        public boolean found()     { return distanceKm >= 0; }
        public boolean isOptimal() { return found() && !approximate; }
    }

    // ── main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "Usage: ScenicSearchParallel <file.tmg> <startIndex> <endIndex>");
            System.exit(1);
        }
        HighwayGraph g     = TmgParser.parse(Path.of(args[0]));
        int          start = Integer.parseInt(args[1]);
        int          end   = Integer.parseInt(args[2]);

        System.out.println("Loaded: " + g.vertexCount() + " vertices, "
                + g.edgeCount() + " edges");
        System.out.println("Start: [" + start + "] " + g.vertex(start).label());
        System.out.println("End:   [" + end   + "] " + g.vertex(end).label());
        System.out.printf("Cores: %d  |  Time limit: %d s  |  Split depth: %d%n",
                Runtime.getRuntime().availableProcessors(),
                TIME_LIMIT_MS / 1000, SPLIT_DEPTH);
        System.out.println("Searching...");

        long   t0      = System.currentTimeMillis();
        Result result  = new ScenicSearchParallel(g).search(start, end);
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
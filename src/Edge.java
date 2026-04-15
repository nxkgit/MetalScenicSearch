import java.util.Arrays;

/**
 * One TMG edge (undirected). Shaping coords, if any, alternate lat, lon, ...
 */
public record Edge(int endpointA, int endpointB, String roadLabel, double[] shapingLatLonPairs) {

    public Edge {
        if (shapingLatLonPairs != null && shapingLatLonPairs.length % 2 != 0) {
            throw new IllegalArgumentException("shaping points must be lat/lon pairs");
        }
    }

    public double[] shapingPoints() {
        return shapingLatLonPairs == null || shapingLatLonPairs.length == 0
                ? new double[0]
                : Arrays.copyOf(shapingLatLonPairs, shapingLatLonPairs.length);
    }

    public int otherEndpoint(int v) {
        if (v == endpointA) return endpointB;
        if (v == endpointB) return endpointA;
        throw new IllegalArgumentException("Vertex " + v + " is not an endpoint of this edge");
    }
}

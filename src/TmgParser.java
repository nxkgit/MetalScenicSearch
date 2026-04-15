import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TmgParser {

    private TmgParser() {}

    public static HighwayGraph parse(Path path) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(in);
        }
    }

    public static HighwayGraph parse(BufferedReader in) throws IOException {
        String formatLine = in.readLine();
        if (formatLine == null) throw new IOException("Empty TMG file");

        String[] fmt = formatLine.trim().split("\\s+");
        if (fmt.length < 3 || !"TMG".equals(fmt[0]))
            throw new IOException("Expected TMG header, got: " + formatLine);

        String version = fmt[1];
        if (!"1.0".equals(version) && !"2.0".equals(version) && !"3.0".equals(version))
            throw new IOException("Unsupported TMG version: " + version);

        String variant = fmt[2];
        if (!"collapsed".equals(variant) && !"simple".equals(variant))
            throw new IOException("Expected collapsed or simple TMG, got: " + variant);

        String countsLine = in.readLine();
        if (countsLine == null) throw new IOException("Missing vertex/edge counts");

        String[] counts = countsLine.trim().split("\\s+");
        int vertexCount = Integer.parseInt(counts[0]);
        int edgeCount   = Integer.parseInt(counts[1]);

        List<Vertex> vertices = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            String line = in.readLine();
            if (line == null) throw new IOException("Expected " + vertexCount + " vertices, got " + i);
            vertices.add(parseVertex(i, line));
        }

        List<Edge> edges = new ArrayList<>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            String line = in.readLine();
            if (line == null) throw new IOException("Expected " + edgeCount + " edges, got " + i);
            edges.add(parseEdge(line));
        }

        return new HighwayGraph(vertices, edges);
    }

    static Vertex parseVertex(int index, String line) {
        int last   = line.lastIndexOf(' ');
        int second = line.lastIndexOf(' ', last - 1);
        if (last <= 0 || second < 0) throw new IllegalArgumentException("Bad vertex line: " + line);
        String label = line.substring(0, second).trim();
        double lat   = Double.parseDouble(line.substring(second + 1, last).trim());
        double lon   = Double.parseDouble(line.substring(last + 1).trim());
        return new Vertex(index, label, lat, lon);
    }

    static Edge parseEdge(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 3) throw new IllegalArgumentException("Bad edge line: " + line);
        int a     = Integer.parseInt(parts[0]);
        int b     = Integer.parseInt(parts[1]);
        String road = parts[2];
        int extra = parts.length - 3;
        double[] shaping;
        if (extra == 0) {
            shaping = new double[0];
        } else {
            if (extra % 2 != 0)
                throw new IllegalArgumentException("Need lat/lon pairs after road: " + line);
            shaping = new double[extra];
            for (int i = 0; i < extra; i++) shaping[i] = Double.parseDouble(parts[3 + i]);
        }
        return new Edge(a, b, road, shaping);
    }
}

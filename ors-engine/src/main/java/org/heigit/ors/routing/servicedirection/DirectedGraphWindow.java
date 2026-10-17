package org.heigit.ors.routing.servicedirection;

import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.BBox;
import org.heigit.ors.routing.RoutingProfile;
import org.heigit.ors.routing.RouteSearchParameters;
import org.heigit.ors.routing.graphhopper.extensions.ORSEdgeFilterFactory;
import org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerPolicy;
import org.heigit.ors.util.TemporaryUtilShelter;
import org.locationtech.jts.geom.Coordinate;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class DirectedGraphWindow {
    public record Node(int id, double lat, double lng, boolean virtual, boolean boundary) {}
    public record Arc(int id, int from, int to, double lengthM, double cost, List<double[]> coordinates) {}
    public record Turn(int from, int to, double cost) {}
    public record Window(String schema, String status, String graphDate, String policySha256,
                         double radiusM, List<Integer> snaps, List<Node> nodes, List<Arc> arcs, List<Turn> turns) {}
    private DirectedGraphWindow() {}

    public static Window read(RoutingProfile profile, RouteSearchParameters parameters, Coordinate[] locations,
                              double radiusM, BooleanSupplier cancelled) throws Exception {
        var gh = profile.getGraphhopper();
        var storage = gh.getGraphHopperStorage();
        var context = TemporaryUtilShelter.createSearchContext(parameters, profile);
        var hints = context.getProperties();
        if (parameters.getCustomModel() != null) hints.putObject("custom_model", parameters.getCustomModel());
        GraphMarkerPolicy.installSelection(storage, hints, parameters.getGraphMarkerSelection());
        var weighting = gh.createWeighting(gh.getProfile(context.profileName()), hints);
        if (!weighting.hasTurnCosts()) throw new IllegalArgumentException("Service direction requires turn costs");
        EdgeFilter filter = new ORSEdgeFilterFactory().createEdgeFilter(hints, context.getEncoder(), storage);
        List<Snap> snaps = new ArrayList<>();
        for (var location : locations) {
            var snap = gh.getLocationIndex().findClosest(location.y, location.x, edge ->
                    filter.accept(edge) && (Double.isFinite(weighting.calcEdgeWeight(edge, false))
                            || Double.isFinite(weighting.calcEdgeWeight(edge, true))));
            if (!snap.isValid() || snap.getQueryDistance() > 5)
                throw new IllegalArgumentException("Service direction snap exceeds 5 metres");
            snaps.add(snap);
        }
        var graph = QueryGraph.create(storage.getBaseGraph(), snaps);
        Weighting queryWeighting = graph.wrapWeighting(weighting);
        double lat = locations[0].y, lng = locations[0].x;
        double dy = radiusM / 110000, dx = dy / Math.cos(Math.toRadians(lat));
        BBox bounds = new BBox(lng - dx, lng + dx, lat - dy, lat + dy);
        Set<Integer> candidates = new TreeSet<>();
        gh.getLocationIndex().query(bounds, id -> {
            var edge = storage.getEdgeIteratorStateForKey(id * 2);
            candidates.add(edge.getBaseNode()); candidates.add(edge.getAdjNode());
        });
        for (int id = storage.getNodes(); id < graph.getNodes(); id++) candidates.add(id);
        var access = graph.getNodeAccess();
        candidates.removeIf(id -> DistanceCalcEarth.DIST_EARTH.calcDist(lat, lng, access.getLat(id), access.getLon(id)) > radiusM);
        String policy = storage.getProperties().get(GraphMarkerPolicy.PROPERTY);
        String sha = policy.isEmpty() ? "" : new com.fasterxml.jackson.databind.ObjectMapper().readTree(policy).path("policySha256").asText();
        return collect(graph, queryWeighting, filter, candidates, storage.getProperties().get("datareader.import.date"),
                sha, radiusM, snaps.stream().map(Snap::getClosestNode).toList(), cancelled);
    }

    static Window collect(QueryGraph graph, Weighting queryWeighting, EdgeFilter filter, Set<Integer> candidates,
                          String graphDate, String policySha256, double radiusM, List<Integer> snaps, BooleanSupplier cancelled) {
        var access = graph.getNodeAccess();
        List<Arc> arcs = new ArrayList<>();
        Map<Integer, List<EdgeIteratorState>> outgoing = new HashMap<>(), incoming = new HashMap<>();
        Set<Integer> boundary = new HashSet<>();
        long until = System.nanoTime() + 2_000_000_000L;
        String status = "COMPLETE";
        List<Turn> turns = new ArrayList<>();
        outer: for (int node : candidates) {
            if (cancelled.getAsBoolean() || System.nanoTime() > until || arcs.size() > 30000) { status = "BUDGET_EXHAUSTED"; break; }
            var iter = graph.createEdgeExplorer().setBaseNode(node);
            while (iter.next()) {
                if (!filter.accept(iter) || !iter.get(queryWeighting.getFlagEncoder().getAccessEnc())) continue;
                double cost = queryWeighting.calcEdgeWeight(iter, false);
                if (!Double.isFinite(cost)) continue;
                if (!candidates.contains(iter.getAdjNode())) { boundary.add(node); continue; }
                var edge = iter.detach(false);
                var points = edge.fetchWayGeometry(FetchMode.ALL);
                List<double[]> coordinates = new ArrayList<>();
                for (int i = 0; i < points.size(); i++) coordinates.add(new double[]{points.getLat(i), points.getLon(i)});
                arcs.add(new Arc(edge.getEdgeKey(), node, edge.getAdjNode(), edge.getDistance(), cost, coordinates));
                outgoing.computeIfAbsent(node, k -> new ArrayList<>()).add(edge);
                incoming.computeIfAbsent(edge.getAdjNode(), k -> new ArrayList<>()).add(edge);
                if (arcs.size() > 30000) { status = "BUDGET_EXHAUSTED"; break outer; }
            }
        }
        if (status.equals("COMPLETE")) {
            outer: for (int node : candidates) for (var from : incoming.getOrDefault(node, List.of())) {
                for (var to : outgoing.getOrDefault(node, List.of())) {
                    if (cancelled.getAsBoolean() || System.nanoTime() > until || turns.size() >= 150000) { status = "BUDGET_EXHAUSTED"; break outer; }
                    if (graph.isVirtualNode(node) && from.getEdge() == to.getEdge()) continue;
                    double cost = queryWeighting.calcTurnWeight(from.getEdge(), node, to.getEdge());
                    if (Double.isFinite(cost)) turns.add(new Turn(from.getEdgeKey(), to.getEdgeKey(), cost));
                }
            }
        }
        List<Node> nodes = candidates.stream().map(id -> new Node(id, access.getLat(id), access.getLon(id),
                graph.isVirtualNode(id), boundary.contains(id))).toList();
        return new Window("service-direction-graph-v1", status, graphDate, policySha256, radiusM, snaps, nodes, arcs, turns);
    }
}

package com.graphhopper.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import java.util.List;
import java.util.Map;

public final class WaypointContinuitySolver extends Router.Solver {
    private final WeightingFactory factory;
    private final GraphHopperStorage storage;
    private final LocationIndex index;
    private final int maximumWaypointDistance;

    public WaypointContinuitySolver(GHRequest request, Map<String, Profile> profiles, RouterConfig config,
                                    EncodedValueLookup lookup, WeightingFactory factory,
                                    GraphHopperStorage storage, LocationIndex index) {
        super(request, profiles, config, lookup);
        this.factory = factory;
        this.storage = storage;
        this.index = index;
        this.maximumWaypointDistance = config.getNonChMaxWaypointDistance();
    }

    @Override protected void checkRequest() {
        super.checkRequest();
        var points = request.getPoints();
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            if (com.graphhopper.util.DistanceCalcEarth.DIST_EARTH.calcDist(a.getLat(), a.getLon(), b.getLat(), b.getLon()) > maximumWaypointDistance)
                throw new com.graphhopper.util.exceptions.PointDistanceExceededException(
                        "Logical route points exceed the flexible routing distance limit", Map.of("from", i - 1, "to", i));
        }
    }

    @Override protected com.graphhopper.routing.weighting.Weighting createWeighting() {
        var hints = new com.graphhopper.util.PMap(request.getHints());
        hints.putObject("custom_model", request.getCustomModel());
        var result = factory.createWeighting(profile, hints, false);
        if (hints.has("block_area")) {
            var area = com.graphhopper.storage.GraphEdgeIdFinder.createBlockArea(storage, index,
                    request.getPoints(), hints, new com.graphhopper.routing.util.FiniteWeightFilter(result));
            result = new com.graphhopper.routing.weighting.BlockAreaWeighting(result, area);
        }
        return new com.graphhopper.routing.weighting.AbstractAdjustedWeighting(result) {
            @Override public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse) {
                WaypointContinuityBudget.checkCancellation();
                return super.calcEdgeWeight(edge, reverse);
            }
            @Override public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse, long time) {
                WaypointContinuityBudget.checkCancellation();
                return super.calcEdgeWeight(edge, reverse, time);
            }
            @Override public String getName() { return superWeighting.getName(); }
        };
    }

    @Override protected com.graphhopper.routing.util.EdgeFilter getSnapFilter() {
        var filter = new com.graphhopper.routing.util.DefaultSnapFilter(weighting,
                lookup.getBooleanEncodedValue(com.graphhopper.routing.ev.Subnetwork.key(profile.getName())));
        return edgeFilterFactory == null ? filter : edgeFilterFactory.createEdgeFilter(
                request.getAdditionalHints(), weighting.getFlagEncoder(), storage, filter);
    }

    private AlgorithmOptions getAlgoOpts() {
        var options = new AlgorithmOptions().setAlgorithm(request.getAlgorithm())
                .setTraversalMode(profile.isTurnCosts() ? com.graphhopper.routing.util.TraversalMode.EDGE_BASED
                        : com.graphhopper.routing.util.TraversalMode.NODE_BASED)
                .setMaxVisitedNodes(getMaxVisitedNodes(request.getHints())).setHints(request.getHints());
        if (edgeFilterFactory != null) options.setEdgeFilter(edgeFilterFactory.createEdgeFilter(
                request.getAdditionalHints(), weighting.getFlagEncoder(), storage));
        return options;
    }

    public static int originalKey(EdgeIteratorState edge) {
        return edge instanceof VirtualEdgeIteratorState virtual ? virtual.getOriginalEdgeKey() : edge.getEdgeKey();
    }

    @Override protected FlexiblePathCalculator createPathCalculator(QueryGraph graph) {
        AlgorithmOptions options = getAlgoOpts();
        int maximumPerSearch = options.getMaxVisitedNodes();
        WaypointContinuityBudget budget = request.getHints().getObject(WaypointContinuityBudget.HINT,
                new WaypointContinuityBudget(options.getMaxVisitedNodes()));
        RoutingAlgorithm[] active = new RoutingAlgorithm[1];
        RoutingAlgorithmFactory factory = (routingGraph, routingWeighting, opts) -> {
            active[0] = new RoutingAlgorithmFactorySimple().createAlgo(routingGraph, routingWeighting, opts);
            return active[0];
        };
        FlexiblePathCalculator delegate = new FlexiblePathCalculator(graph, factory, weighting, options);
        return new FlexiblePathCalculator(graph, factory, weighting, options) {
            private int visits;

            private List<Path> calculate(int from, int to, long time, EdgeRestrictions restrictions) {
                options.setMaxVisitedNodes(Math.min(maximumPerSearch, budget.remaining()));
                active[0] = null;
                try {
                    return delegate.calcPaths(from, to, time, restrictions);
                } finally {
                    // The calculator keeps a stale count when a search exceeds its limit.
                    int count = active[0] == null ? 0 : active[0].getVisitedNodes();
                    visits += count;
                    budget.consume(count);
                }
            }

            @Override public List<Path> calcPaths(int from, int to, long time, EdgeRestrictions restrictions) {
                WaypointContinuityBudget.checkCancellation();
                Integer incoming = request.getHints().getObject("ors.continuity_incoming", null);
                Integer target = request.getHints().getObject("ors.continuity_target", null);
                var turns = graph.wrapWeighting(weighting);
                var targetKeys = new java.util.ArrayList<Integer>();
                int targetEdge = restrictions.getTargetInEdge();
                var edges = graph.createEdgeExplorer().setBaseNode(to);
                while (edges.next()) {
                    int key = originalKey(edges.detach(false)) ^ 1;
                    if (Double.isFinite(turns.calcEdgeWeightWithAccess(edges, true))) targetKeys.add(key);
                    if (target != null && key == target) targetEdge = edges.getEdge();
                }
                targetKeys.sort(Integer::compareTo);
                request.getHints().putObject("ors.continuity_targets", targetKeys);
                if (target != null && targetEdge < 0)
                    throw new ConnectionNotFoundException("Target approach unavailable", Map.of());
                var base = new EdgeRestrictions();
                base.setSourceOutEdge(restrictions.getSourceOutEdge());
                base.setTargetInEdge(targetEdge);
                base.getUnfavoredEdges().addAll(restrictions.getUnfavoredEdges());
                List<Path> paths;
                if (incoming == null) paths = calculate(from, to, time, base);
                else if (from == to && target == null) {
                    // A repeated point must retain the preceding directed edge.
                    paths = calculate(from, to, time, base);
                } else {
                    int reverseEdge = -1;
                    edges = graph.createEdgeExplorer().setBaseNode(from);
                    while (edges.next()) if (originalKey(edges.detach(false)) == (incoming ^ 1)) reverseEdge = edges.getEdge();
                    if (reverseEdge < 0) throw new ConnectionNotFoundException("Continuation snap mismatch", Map.of());
                    Path best = null;
                    edges = graph.createEdgeExplorer().setBaseNode(from);
                    while (edges.next()) {
                        if (edges.getEdge() == reverseEdge || !Double.isFinite(turns.calcEdgeWeightWithAccess(edges, false))) continue;
                        double turn = turns.calcTurnWeight(reverseEdge, from, edges.getEdge());
                        if (!Double.isFinite(turn)) continue;
                        if (base.getSourceOutEdge() >= 0 && base.getSourceOutEdge() != edges.getEdge()) continue;
                        EdgeRestrictions candidate = new EdgeRestrictions();
                        candidate.setSourceOutEdge(edges.getEdge());
                        candidate.setTargetInEdge(base.getTargetInEdge());
                        candidate.getUnfavoredEdges().addAll(base.getUnfavoredEdges());
                        try {
                            for (Path path : calculate(from, to, time, candidate)) {
                                if (path.isFound() && (best == null || path.getWeight() + turn < best.getWeight())) {
                                    path.setWeight(path.getWeight() + turn);
                                    best = path;
                                }
                            }
                        } catch (ConnectionNotFoundException ignored) {
                            // An unreachable departure does not exclude the other legal departures.
                        }
                    }
                    if (best == null) throw new ConnectionNotFoundException("No legal waypoint continuation", Map.of());
                    paths = List.of(best);
                }
                if (!paths.isEmpty() && paths.get(0).getEdgeCount() > 0)
                    request.getHints().putObject("ors.continuity_last", originalKey(paths.get(0).getFinalEdge()));
                else if (incoming != null) request.getHints().putObject("ors.continuity_last", incoming);
                request.getHints().putObject("ors.continuity_weight", paths.get(0).getWeight());
                return paths;
            }
            @Override public int getVisitedNodes() { return visits; }
            @Override public String getDebugString() { return delegate.getDebugString(); }
        };
    }
}

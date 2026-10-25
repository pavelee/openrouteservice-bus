package com.graphhopper.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.PMap;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.shapes.GHPoint;
import org.heigit.ors.routing.graphhopper.extensions.ORSRouter;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WaypointContinuitySolverTest {
    private final CarFlagEncoder encoder = new CarFlagEncoder(new PMap().putObject("turn_costs", true));

    private GraphHopperStorage graph(boolean loop) {
        var manager = new EncodingManager.Builder().add(encoder)
                .add(new SimpleBooleanEncodedValue(Subnetwork.key("car"), false)).build();
        var graph = new GraphBuilder(manager).withTurnCosts(true).create();
        double[][] points = {{52,21},{52,21.002},{52.002,21.002},{52.002,21}};
        for (int i=0;i<points.length;i++) graph.getNodeAccess().setNode(i,points[i][0],points[i][1]);
        graph.edge(0,1).setDistance(140).set(encoder.getAccessEnc(),true,true).set(encoder.getAverageSpeedEnc(),30);
        if (loop) for (int[] edge : new int[][]{{1,2},{2,3},{3,0}})
            graph.edge(edge[0],edge[1]).setDistance(150).set(encoder.getAccessEnc(),true,true).set(encoder.getAverageSpeedEnc(),30);
        return graph;
    }

    private ORSRouter router(GraphHopperStorage graph, LocationIndexTree index) {
        return router(graph, index, new DefaultWeightingFactory(graph, graph.getEncodingManager()));
    }

    private ORSRouter router(GraphHopperStorage graph, LocationIndexTree index, WeightingFactory factory) {
        return router(graph, index, factory, new RouterConfig());
    }

    private ORSRouter router(GraphHopperStorage graph, LocationIndexTree index, WeightingFactory factory, RouterConfig config) {
        var profile = new Profile("car").setVehicle("car").setWeighting("shortest").setTurnCosts(true);
        return new ORSRouter(graph,index,Map.of("car",profile),new PathDetailsBuilderFactory(),
                new TranslationMap().doImport(),config,factory,Map.of(),Map.of());
    }

    private GHRequest request(double from, double to, Integer incoming) {
        var request = new GHRequest(new GHPoint(52,from),new GHPoint(52,to));
        request.setProfile("car").setAlgorithm("dijkstrabi");
        request.putHint("ors.continuity_track",true);
        request.putHint("u_turn_costs",-1);
        if (incoming != null) request.putHint("ors.continuity_incoming",incoming);
        return request;
    }

    @Test void virtualWaypointUsesLegalLoopWithHeadings() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph,graph.getDirectory());
            index.prepareIndex(); var router = router(graph,index);
            var first = router.route(request(21.0002,21.001,null)); assertFalse(first.hasErrors(),first.getErrors().toString());
            Integer incoming = first.getHints().getObject("ors.continuity_last",null); assertNotNull(incoming);
            var next = request(21.001,21.0002,incoming); next.setHeadings(java.util.List.of(270.0,Double.NaN));
            GHResponse result = router.route(next); assertFalse(result.hasErrors(),result.getErrors().toString());
            assertTrue(result.getBest().getDistance()>400);
            var geometry = result.getBest().getPoints(); assertTrue(geometry.getLon(1)>geometry.getLon(0));
            index.close();
        }
    }

    @Test void explicitExceptionAllowsVirtualReversal() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph,graph.getDirectory());
            index.prepareIndex(); var router = router(graph,index);
            var response = router.route(request(21.001,21.0002,null));
            assertFalse(response.hasErrors()); assertTrue(response.getBest().getDistance()<100);
            index.close();
        }
    }

    @Test void noLegalContinuationReturnsNoRoute() {
        try (var graph = graph(false)) {
            var index = new LocationIndexTree(graph,graph.getDirectory());
            index.prepareIndex(); var router = router(graph,index);
            var first = router.route(request(21.0002,21.002,null)); assertFalse(first.hasErrors());
            var next = router.route(request(21.002,21.0002,first.getHints().getObject("ors.continuity_last",null)));
            assertTrue(next.hasErrors());
            index.close();
        }
    }
    @Test void continuationRespectsTurnRestrictionAtRealNode() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph,graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph,index);
                var first = router.route(request(21.0002,21.002,null)); assertFalse(first.hasErrors());
                graph.getTurnCostStorage().set(graph.getEncodingManager().getDecimalEncodedValue(
                        com.graphhopper.routing.ev.TurnCost.key("car")),0,1,1,Double.POSITIVE_INFINITY);
                var next = router.route(request(21.002,21.0002,first.getHints().getObject("ors.continuity_last",null)));
                assertTrue(next.hasErrors());
            } finally { index.close(); }
        }
    }

    @Test void jointChoiceCanReplaceAnEarlierGreedyApproach() {
        try (var graph = graph(true)) {
            graph.getNodeAccess().setNode(4, 51.999, 21.001);
            graph.edge(0,4).setDistance(80).set(encoder.getAccessEnc(),true,true).set(encoder.getAverageSpeedEnc(),30);
            var alternative = graph.edge(4,1).setDistance(80).set(encoder.getAccessEnc(),true,true).set(encoder.getAverageSpeedEnc(),30);
            graph.getTurnCostStorage().set(graph.getEncodingManager().getDecimalEncodedValue(
                    com.graphhopper.routing.ev.TurnCost.key("car")), 0, 1, alternative.getEdge(), Double.POSITIVE_INFINITY);
            var index = new LocationIndexTree(graph,graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph,index);
                var greedy = router.route(request(21.0002,21.002,null));
                assertFalse(greedy.hasErrors());
                var greedyExit = router.route(request(21.002,21.0002,greedy.getHints().getObject("ors.continuity_last",null)));
                assertFalse(greedyExit.hasErrors());
                var approach = request(21.0002,21.002,null);
                approach.putHint("ors.continuity_target", alternative.getEdgeKey());
                var joint = router.route(approach);
                assertFalse(joint.hasErrors(),joint.getErrors().toString());
                assertEquals(alternative.getEdgeKey(), (int) joint.getHints().getObject("ors.continuity_last",null));
                var jointExit = router.route(request(21.002,21.0002,joint.getHints().getObject("ors.continuity_last",null)));
                assertFalse(jointExit.hasErrors());
                assertTrue(joint.getBest().getDistance() > greedy.getBest().getDistance());
                assertTrue(joint.getBest().getDistance() + jointExit.getBest().getDistance()
                        < greedy.getBest().getDistance() + greedyExit.getBest().getDistance());
            } finally { index.close(); }
        }
    }

    @Test void forcedVirtualArrivalPreservesItsDirectedKey() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph,graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph,index);
                var forced = request(21.0002,21.001,null);
                forced.putHint("ors.continuity_target", 1);
                var response = router.route(forced);
                assertFalse(response.hasErrors(),response.getErrors().toString());
                assertEquals(1, (int) response.getHints().getObject("ors.continuity_last",null));
                assertTrue(response.getBest().getDistance()>400);
                var next = router.route(request(21.001,21.0002,1));
                assertFalse(next.hasErrors());
                assertTrue(next.getBest().getDistance()<100);
            } finally { index.close(); }
        }
    }

    @Test void repeatedWaypointRetainsArrivalWithoutAddingATurn() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph, graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph, index);
                var first = router.route(request(21.0002, 21.001, null));
                Integer incoming = first.getHints().getObject("ors.continuity_last", null);
                var repeated = router.route(request(21.001, 21.001, incoming));
                assertFalse(repeated.hasErrors(), repeated.getErrors().toString());
                assertEquals(0, repeated.getBest().getDistance());
                assertEquals(incoming, repeated.getHints().getObject("ors.continuity_last", null));
                var continuation = router.route(request(21.001, 21.0002, incoming));
                assertFalse(continuation.hasErrors());
                assertTrue(continuation.getBest().getDistance() > 400);
            } finally { index.close(); }
        }
    }

    @Test void separateSearchesConsumeOneSharedBudget() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph, graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph, index);
                var budget = new WaypointContinuityBudget(1000);
                for (int i = 0; i < 2; i++) {
                    int before = budget.remaining();
                    var request = request(21.0002, 21.001, null);
                    request.putHint(WaypointContinuityBudget.HINT, budget);
                    var result = router.route(request);
                    assertFalse(result.hasErrors());
                    int visits = result.getHints().getInt("visited_nodes.sum", 0);
                    assertTrue(visits > 0);
                    assertEquals(before - visits, budget.remaining());
                }
                var limited = request(21.0002, 21.001, null);
                limited.putHint(WaypointContinuityBudget.HINT, new WaypointContinuityBudget(1));
                assertThrows(WaypointContinuityBudget.Exhausted.class, () -> router.route(limited));
            } finally { index.close(); }
        }
    }

    @Test void interruptionStopsTheSolverAndKeepsTheInterruptFlag() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph, graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph, index);
                Thread.currentThread().interrupt();
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> router.route(request(21.0002, 21.001, null)));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); index.close(); }
        }
    }

    @Test void interruptionDuringWeightEvaluationStopsTheSearch() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph, graph.getDirectory());
            try {
                index.prepareIndex();
                var original = new DefaultWeightingFactory(graph, graph.getEncodingManager());
                WeightingFactory interrupting = (profile, hints, disableTurnCosts) ->
                        new com.graphhopper.routing.weighting.AbstractAdjustedWeighting(
                                original.createWeighting(profile, hints, disableTurnCosts)) {
                            @Override public double calcEdgeWeight(com.graphhopper.util.EdgeIteratorState edge, boolean reverse) {
                                Thread.currentThread().interrupt();
                                return super.calcEdgeWeight(edge, reverse);
                            }
                            @Override public String getName() { return superWeighting.getName(); }
                        };
                var router = router(graph, index, interrupting);
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> router.route(request(21.0002, 21.001, null)));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); index.close(); }
        }
    }

    @Test void lowerPerSearchLimitStillAppliesWithASharedBudget() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph, graph.getDirectory());
            try {
                index.prepareIndex(); var router = router(graph, index);
                var request = request(21.0002, 21.001, null);
                var budget = new WaypointContinuityBudget(1000);
                request.putHint(WaypointContinuityBudget.HINT, budget);
                request.putHint(com.graphhopper.util.Parameters.Routing.MAX_VISITED_NODES, 1);
                var result = router.route(request);
                assertTrue(result.hasErrors());
                assertTrue(budget.remaining() < 1000);
            } finally { index.close(); }
        }
    }

    @Test void existingFlexibleWaypointDistanceLimitStillApplies() {
        try (var graph = graph(true)) {
            var index = new LocationIndexTree(graph, graph.getDirectory());
            try {
                index.prepareIndex();
                var config = new RouterConfig();
                config.setNonChMaxWaypointDistance(10);
                var router = router(graph, index, new DefaultWeightingFactory(graph, graph.getEncodingManager()), config);
                var result = router.route(request(21.0002, 21.001, null));
                assertTrue(result.hasErrors());
                assertInstanceOf(com.graphhopper.util.exceptions.PointDistanceExceededException.class, result.getErrors().get(0));
            } finally { index.close(); }
        }
    }

}

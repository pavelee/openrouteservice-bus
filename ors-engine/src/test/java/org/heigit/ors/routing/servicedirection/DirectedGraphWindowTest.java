package org.heigit.ors.routing.servicedirection;

import com.graphhopper.routing.ev.TurnCost;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.DefaultTurnCostProvider;
import com.graphhopper.routing.weighting.ShortestWeighting;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.PMap;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectedGraphWindowTest {
    @Test void preservesTurnRestrictionsAndOneWayAccess() {
        var encoder = new CarFlagEncoder(new PMap().putObject("turn_costs", true));
        var em = EncodingManager.create(encoder);
        try (var graph = new GraphBuilder(em).withTurnCosts(true).create()) {
            for (int i = 0; i < 3; i++) graph.getNodeAccess().setNode(i, 52, 21 + i * .001);
            var first = graph.edge(0, 1).setDistance(70).set(encoder.getAccessEnc(), true, false).set(encoder.getAverageSpeedEnc(), 30);
            var second = graph.edge(1, 2).setDistance(70).set(encoder.getAccessEnc(), true, true).set(encoder.getAverageSpeedEnc(), 30);
            graph.getTurnCostStorage().set(em.getDecimalEncodedValue(TurnCost.key("car")), first.getEdge(), 1, second.getEdge(), Double.POSITIVE_INFINITY);
            var weighting = new ShortestWeighting(encoder, new DefaultTurnCostProvider(encoder, graph.getTurnCostStorage()));
            var result = DirectedGraphWindow.collect(QueryGraph.create(graph, List.of()), weighting, weighting, EdgeFilter.ALL_EDGES,
                    new TreeSet<>(List.of(0, 1, 2)), "date", "policy", 2005, List.of(0, 1, 2), () -> false);
            assertEquals("COMPLETE", result.status());
            assertTrue(result.arcs().stream().noneMatch(a -> a.from() == 1 && a.to() == 0));
            assertTrue(result.turns().stream().noneMatch(t -> t.from() == first.getEdgeKey() && t.to() == second.getEdgeKey()));
        }
    }

    @Test void virtualStopNeverAddsTurnaroundEvenWithFreeTurnCosts() {
        var encoder = new CarFlagEncoder();
        try (GraphHopperStorage graph = new GraphBuilder(EncodingManager.create(encoder)).create()) {
            graph.getNodeAccess().setNode(0, 52, 21);
            graph.getNodeAccess().setNode(1, 52, 21.002);
            graph.edge(0, 1).setDistance(140).set(encoder.getAccessEnc(), true, true).set(encoder.getAverageSpeedEnc(), 30);
            try (var index = new LocationIndexTree(graph, graph.getDirectory())) {
                index.prepareIndex();
                var snap = index.findClosest(52, 21.001, EdgeFilter.ALL_EDGES);
                var query = QueryGraph.create(graph, List.of(snap));
                var result = DirectedGraphWindow.collect(query, query.wrapWeighting(new ShortestWeighting(encoder)), query.wrapWeighting(new ShortestWeighting(encoder)), EdgeFilter.ALL_EDGES,
                        new TreeSet<>(List.of(0, 1, snap.getClosestNode())), "date", "policy", 2005, List.of(snap.getClosestNode()), () -> false);
                var arcs = new HashMap<Integer, DirectedGraphWindow.Arc>();
                result.arcs().forEach(a -> arcs.put(a.id(), a));
                assertFalse(result.turns().isEmpty());
                assertTrue(result.turns().stream().noneMatch(t -> arcs.get(t.from()).to() == snap.getClosestNode() && (t.from() ^ 1) == t.to()));
            }
        }
    }

    @Test void cancellationIsNotNoPath() {
        var encoder = new CarFlagEncoder();
        try (var graph = new GraphBuilder(EncodingManager.create(encoder)).create()) {
            graph.getNodeAccess().setNode(0, 52, 21);
            var result = DirectedGraphWindow.collect(QueryGraph.create(graph, List.of()), new ShortestWeighting(encoder), new ShortestWeighting(encoder), EdgeFilter.ALL_EDGES,
                    Set.of(0), "date", "policy", 2005, List.of(0), () -> true);
            assertEquals("BUDGET_EXHAUSTED", result.status());
        }
    }
}

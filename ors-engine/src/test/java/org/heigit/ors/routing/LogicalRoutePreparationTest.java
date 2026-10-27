package org.heigit.ors.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.DefaultWeightingFactory;
import com.graphhopper.routing.RouterConfig;
import com.graphhopper.routing.WaypointContinuitySolver;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.exceptions.RouteNotFoundException;
import org.heigit.ors.exceptions.ServerLimitExceededException;
import org.heigit.ors.routing.graphhopper.extensions.ORSGraphHopper;
import org.heigit.ors.routing.graphhopper.extensions.ORSRouter;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LogicalRoutePreparationTest {
    private static final String SHORTEST = "car_shortest_with_turn_costs";
    private static final String FASTEST = "car_fastest_with_turn_costs";

    private static class Fixture implements AutoCloseable {
        final GraphHopperStorage graph;
        final LocationIndexTree index;
        final RoutingProfile profile = mock(RoutingProfile.class);
        final ProfileProperties properties = new ProfileProperties();
        final List<Weighting[]> preparations = new ArrayList<>();
        int weightings;
        boolean interrupt;

        Fixture(boolean loop) {
            var encoder = new CarFlagEncoder(new PMap().putObject("turn_costs", true));
            var manager = new EncodingManager.Builder().add(encoder)
                    .add(new SimpleBooleanEncodedValue(Subnetwork.key(SHORTEST), false))
                    .add(new SimpleBooleanEncodedValue(Subnetwork.key(FASTEST), false)).build();
            graph = new GraphBuilder(manager).withTurnCosts(true).create();
            double[][] points = {{52,21},{52,21.002},{52.002,21.002},{52.002,21},{52.001,21.003}};
            for (int i = 0; i < points.length; i++)
                graph.getNodeAccess().setNode(i, points[i][0], points[i][1]);
            graph.edge(0,1).setDistance(140).set(encoder.getAccessEnc(), true, true).set(encoder.getAverageSpeedEnc(), 30);
            if (loop) for (int[] edge : new int[][]{{1,2},{2,3},{3,0},{1,4},{4,2}})
                graph.edge(edge[0],edge[1]).setDistance(150).set(encoder.getAccessEnc(), true, true)
                        .set(encoder.getAverageSpeedEnc(), 30);
            index = new LocationIndexTree(graph, graph.getDirectory());
            index.prepareIndex();
            var original = new DefaultWeightingFactory(graph, manager);
            WeightingFactory factory = (p, hints, disableTurns) -> {
                weightings++;
                return new AbstractAdjustedWeighting(original.createWeighting(p, hints, disableTurns)) {
                    @Override public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse) {
                        if (interrupt) Thread.currentThread().interrupt();
                        return super.calcEdgeWeight(edge, reverse);
                    }
                    @Override public String getName() { return superWeighting.getName(); }
                };
            };
            var config = new RouterConfig();
            var router = new ORSRouter(graph, index, Map.of(
                    SHORTEST, new Profile(SHORTEST).setVehicle("car").setWeighting("shortest").setTurnCosts(true),
                    FASTEST, new Profile(FASTEST).setVehicle("car").setWeighting("fastest").setTurnCosts(true)),
                    new PathDetailsBuilderFactory(), new TranslationMap().doImport(), config, factory, Map.of(), Map.of());
            var gh = mock(ORSGraphHopper.class);
            when(gh.getEncodingManager()).thenReturn(manager);
            when(gh.getGraphHopperStorage()).thenReturn(graph);
            when(gh.getRouterConfig()).thenReturn(config);
            when(gh.route(any())).thenAnswer(call -> {
                GHRequest request = call.getArgument(0);
                Weighting[] preparation = request.getHints().getObject(WaypointContinuitySolver.PREPARED_WEIGHTING_HINT, null);
                if (preparation != null && !preparations.contains(preparation)) preparations.add(preparation);
                return router.route(request);
            });
            properties.getBuild().getEncoderOptions().setTurnCosts(true);
            properties.getService().setMaximumVisitedNodes(100000);
            properties.getService().setMaximumSnappingRadius(400);
            when(profile.getGraphhopper()).thenReturn(gh);
            when(profile.getProfileConfiguration()).thenReturn(properties);
            when(profile.getProfileProperties()).thenReturn(properties);
        }

        RoutingRequest request(double... longitudes) throws Exception {
            var request = new RoutingRequest();
            request.setRoutingProfile(profile);
            request.setContinueStraight(true);
            request.setCoordinates(java.util.Arrays.stream(longitudes).mapToObj(lon -> new Coordinate(lon,52))
                    .toArray(Coordinate[]::new));
            request.getSearchParameters().setProfileType(RoutingProfileType.GH_CAR);
            request.getSearchParameters().setWeightingMethod(WeightingMethod.SHORTEST);
            request.getSearchParameters().setProfileName(SHORTEST);
            return request;
        }

        @Override public void close() { index.close(); graph.close(); }
    }

    @Test void severalDeparturesKeepTheSameGeometryAndWeightingIsPreparedOnce() throws Exception {
        try (var fixture = new Fixture(true)) {
            var before = fixture.request(21.0002,21.002,21.0002).computeRoute();
            assertTrue(fixture.weightings > 1);
            fixture.weightings = 0;
            var after = RoutingRequest.computeLogicalRoutes(List.of(fixture.request(21.0002,21.002,21.0002)), List.of());
            assertArrayEquals(before[0].getGeometry(), after.get(0)[0].getGeometry());
            assertEquals(before[0].getSummary().getDistance(), after.get(0)[0].getSummary().getDistance());
            assertEquals(1, fixture.weightings);
            assertNull(fixture.preparations.get(0)[0]);
        }
    }

    @Test void groupsAndLaterRequestsPrepareTheirOwnWeightings() throws Exception {
        try (var fixture = new Fixture(true)) {
            var first = fixture.request(21.0002,21.001);
            var second = fixture.request(21.001,21.0018);
            second.getSearchParameters().setWeightingMethod(WeightingMethod.FASTEST);
            second.getSearchParameters().setProfileName(FASTEST);
            var result = RoutingRequest.computeLogicalRoutes(List.of(first, second), List.of());
            assertEquals(2, result.size());
            assertEquals(2, fixture.weightings);
            assertEquals(2, fixture.preparations.size());
            assertNotSame(fixture.preparations.get(0), fixture.preparations.get(1));
            RoutingRequest.computeLogicalRoutes(List.of(fixture.request(21.0002,21.001)), List.of());
            assertEquals(3, fixture.weightings);
            fixture.preparations.forEach(preparation -> assertNull(preparation[0]));
        }
    }

    @Test void repeatedPointKeepsContinuityWithPreparedWeighting() throws Exception {
        try (var fixture = new Fixture(true)) {
            var before = fixture.request(21.0002,21.001,21.001,21.0002).computeRoute();
            fixture.weightings = 0;
            var after = RoutingRequest.computeLogicalRoutes(
                    List.of(fixture.request(21.0002,21.001,21.001,21.0002)), List.of());
            assertArrayEquals(before[0].getGeometry(), after.get(0)[0].getGeometry());
            assertEquals(1, fixture.weightings);
        }
    }

    @Test void noLegalDepartureStillFailsAndReleasesPreparedWeighting() {
        try (var fixture = new Fixture(false)) {
            assertThrows(RouteNotFoundException.class, () -> RoutingRequest.computeLogicalRoutes(
                    List.of(fixture.request(21.0002,21.002,21.0002)), List.of()));
            assertEquals(1, fixture.preparations.size());
            assertNull(fixture.preparations.get(0)[0]);
        }
    }

    @Test void budgetExhaustionStillFailsAndReleasesPreparedWeighting() {
        try (var fixture = new Fixture(true)) {
            fixture.properties.getService().setMaximumVisitedNodes(1);
            assertThrows(ServerLimitExceededException.class, () -> RoutingRequest.computeLogicalRoutes(
                    List.of(fixture.request(21.0002,21.001)), List.of()));
            assertEquals(1, fixture.preparations.size());
            assertNull(fixture.preparations.get(0)[0]);
        }
    }

    @Test void cancellationReleasesPreparedWeightingAndKeepsTheInterruptFlag() {
        try (var fixture = new Fixture(true)) {
            fixture.interrupt = true;
            try {
                assertThrows(CancellationException.class, () -> RoutingRequest.computeLogicalRoutes(
                        List.of(fixture.request(21.0002,21.001)), List.of()));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, fixture.preparations.size());
                assertNull(fixture.preparations.get(0)[0]);
            } finally { Thread.interrupted(); }
        }
    }
}

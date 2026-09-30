package org.heigit.ors.routing.graphhopper.extensions.heading;

import com.graphhopper.GHRequest;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.DefaultWeightingFactory;
import com.graphhopper.routing.RouterConfig;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.PointList;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.shapes.GHPoint;
import org.heigit.ors.config.profile.ServiceProperties;
import org.heigit.ors.routing.WayPointBearing;
import org.heigit.ors.routing.graphhopper.extensions.ORSRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HeadingAwareLocationIndexTest {
    private final CarFlagEncoder encoder = new CarFlagEncoder();
    private final Profile profile = new Profile("car").setVehicle("car").setWeighting("fastest");
    private GraphHopperStorage graph;
    private LocationIndexTree raw;
    private HeadingAwareLocationIndex index;
    private WeightingFactory factory;
    private int baselineId;
    private int alternativeId;

    @BeforeEach
    void buildGraph() {
        EncodingManager manager = new EncodingManager.Builder().add(encoder)
                .add(new SimpleBooleanEncodedValue(Subnetwork.key("car"), false)).build();
        graph = new GraphBuilder(manager).create();
        graph.getNodeAccess().setNode(0, 52.0004, 20.9997);
        graph.getNodeAccess().setNode(1, 52.0004, 21.0005);
        graph.getNodeAccess().setNode(2, 52.0, 21.0002);
        graph.getNodeAccess().setNode(3, 52.0008, 21.0002);
        baselineId = graph.edge(0, 1).setDistance(55).set(encoder.getAccessEnc(), true, true)
                .set(encoder.getAverageSpeedEnc(), 30).getEdge();
        alternativeId = graph.edge(2, 3).setDistance(89).set(encoder.getAccessEnc(), true, false)
                .set(encoder.getAverageSpeedEnc(), 30).getEdge();
        graph.edge(0, 2).setDistance(60).set(encoder.getAccessEnc(), true, true).set(encoder.getAverageSpeedEnc(), 30);
        graph.edge(1, 3).setDistance(50).set(encoder.getAccessEnc(), true, true).set(encoder.getAverageSpeedEnc(), 30);
        raw = new LocationIndexTree(graph, graph.getDirectory());
        raw.prepareIndex();
        index = new HeadingAwareLocationIndex(raw);
        factory = index.captureWeighting(new DefaultWeightingFactory(graph, manager));
    }

    @AfterEach
    void closeGraph() {
        raw.close();
        graph.close();
    }

    private GHRequest request(double heading) {
        GHRequest request = new GHRequest(new GHPoint(52.0004, 21), new GHPoint(52.0004, 21), heading, heading);
        request.putHint(HeadingAwareLocationIndex.DEVIATIONS_HINT, List.of(30.0, 30.0));
        request.setProfile("car");
        request.setMaxSearchDistance(new double[]{400, 400});
        return request;
    }

    private void initializeWeighting(GHRequest request) {
        factory.createWeighting(profile, request.getHints(), false);
    }

    @Test
    void choosesOtherBranchBeforeQueryGraph() {
        GHRequest request = request(0);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(alternativeId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertEquals("STRICT", scope.diagnostics().get(0).get("decision"));
            assertEquals(baselineId, scope.diagnostics().get(0).get("baselineEdgeId"));
        }
    }

    @Test
    void absentHeadingAndAbsentContextPreserveExactSnap() {
        GHRequest request = request(Double.NaN);
        var baseline = raw.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(baseline.getClosestEdge().getEdge(), index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertTrue(scope.diagnostics().isEmpty());
        }
        assertEquals(baseline.getClosestEdge().getEdge(), index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
    }

    @Test
    void derivedContinueStraightHeadingDoesNotSelectAnotherBranch() {
        GHRequest request = request(0);
        request.putHint(HeadingAwareLocationIndex.EXPLICIT_HEADING_HINT, false);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(baselineId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertTrue(scope.diagnostics().isEmpty());
        }
    }

    @Test
    void compatibleBaselineDoesNotChange() {
        GHRequest request = request(90);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(baselineId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertEquals("BASELINE_COMPATIBLE", scope.diagnostics().get(0).get("decision"));
            assertEquals(0, scope.diagnostics().get(0).get("candidateCountWithin30M"));
        }
    }

    @Test
    void rejectsForbiddenAndOppositeOneWayCandidates() {
        GHRequest request = request(180);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(baselineId, index.findClosest(52.0004, 21,
                    edge -> edge.getEdge() == baselineId || edge.getEdge() == alternativeId).getClosestEdge().getEdge());
            assertEquals("BASELINE_NO_MATCH", scope.diagnostics().get(0).get("decision"));
        }
        request = request(0);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(baselineId, index.findClosest(52.0004, 21,
                    edge -> edge.getEdge() == baselineId).getClosestEdge().getEdge());
        }
    }

    @Test
    void respectsRequestRadiusAndLocalLimit() {
        GHRequest request = request(0);
        request.setMaxSearchDistance(new double[]{5, 5});
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(baselineId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
        }
    }

    @Test
    void repeatedCoordinateUsesOccurrenceOrder() {
        GHRequest request = request(0).setHeadings(List.of(0.0, 90.0));
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(alternativeId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertEquals(baselineId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertEquals("START", scope.diagnostics().get(0).get("role"));
            assertEquals("END", scope.diagnostics().get(1).get("role"));
        }
    }

    @Test
    void localPillarGeometryOverridesWholeEdgeDirection() {
        graph.getNodeAccess().setNode(2, 52.0, 21.0012);
        graph.getNodeAccess().setNode(3, 52.0, 21.0016);
        PointList geometry = new PointList();
        geometry.add(52.0002, 21.0002);
        geometry.add(52.0006, 21.0002);
        geometry.add(52.0008, 21.0016);
        graph.getEdgeIteratorState(alternativeId, Integer.MIN_VALUE).setWayGeometry(geometry);
        raw.close();
        raw = new LocationIndexTree(graph, new RAMDirectory());
        raw.prepareIndex();
        index = new HeadingAwareLocationIndex(raw);
        factory = index.captureWeighting(new DefaultWeightingFactory(graph, graph.getEncodingManager()));
        GHRequest request = request(0);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            assertEquals(alternativeId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
        }
    }

    @Test
    void nestedRequestRestoresOuterOccurrenceCursor() {
        GHRequest outer = request(0);
        try (var outerScope = index.begin(outer)) {
            initializeWeighting(outer);
            assertEquals(baselineId, scopedEdge(90));
            assertEquals(alternativeId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
            assertEquals("START", outerScope.diagnostics().get(0).get("role"));
        }
    }

    @Test
    void unrecognizedLookupDoesNotConsumeOccurrence() {
        GHRequest request = request(0);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            index.findClosest(52.0004, 20.9997, EdgeFilter.ALL_EDGES);
            assertTrue(scope.diagnostics().isEmpty());
            assertEquals(alternativeId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
        }
    }

    @Test
    void threadsDoNotShareHeadingsAndExceptionClearsContext() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var north = executor.submit(() -> scopedEdge(0));
            var east = executor.submit(() -> scopedEdge(90));
            assertEquals(alternativeId, north.get());
            assertEquals(baselineId, east.get());
        } finally {
            executor.shutdownNow();
        }
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = index.begin(request(0))) {
                initializeWeighting(request(0));
                throw new IllegalStateException("test failure");
            }
        });
        assertEquals(baselineId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
    }

    private int scopedEdge(double heading) {
        GHRequest request = request(heading);
        try (var scope = index.begin(request)) {
            initializeWeighting(request);
            return index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge();
        }
    }

    @Test
    void routerCreatesQueryGraphFromSelectedSnapAndClearsContext() {
        ORSRouter router = new ORSRouter(graph, index, Map.of("car", profile), new PathDetailsBuilderFactory(),
                new TranslationMap().doImport(), new RouterConfig(), factory, Map.of(), Map.of());
        GHRequest request = new GHRequest(new GHPoint(52.0004, 20.9997), new GHPoint(52.0004, 21), Double.NaN, 0);
        request.setProfile("car");
        request.putHint(HeadingAwareLocationIndex.DEVIATIONS_HINT, List.of(30.0, 30.0));
        var response = router.route(request);
        assertFalse(response.hasErrors(), response.getErrors().toString());
        PointList route = response.getBest().getPoints();
        assertEquals(21.0002, route.getLon(route.size() - 1), 0.000001);
        assertEquals(baselineId, index.findClosest(52.0004, 21, EdgeFilter.ALL_EDGES).getClosestEdge().getEdge());
    }

    @Test
    void defaultConfigurationIsOffAndMergingHonorsExplicitOff() {
        ServiceProperties defaults = new ServiceProperties();
        assertFalse(Boolean.TRUE.equals(defaults.getHeadingAwareSnap()));
        defaults.setHeadingAwareSnap(true);
        ServiceProperties profileProperties = new ServiceProperties();
        profileProperties.setHeadingAwareSnap(false);
        profileProperties.merge(defaults);
        assertFalse(profileProperties.getHeadingAwareSnap());
        assertEquals(30, new WayPointBearing(31, 30).getDeviation());
        assertEquals(100, new WayPointBearing(31).getDeviation());
        assertTrue(Double.isNaN(new WayPointBearing(-1, 30).getValue()));
    }
}

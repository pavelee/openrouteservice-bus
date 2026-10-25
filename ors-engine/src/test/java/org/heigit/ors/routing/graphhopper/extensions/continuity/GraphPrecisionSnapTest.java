package org.heigit.ors.routing.graphhopper.extensions.continuity;

import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalcEarth;
import org.junit.jupiter.api.Test;
import com.graphhopper.GHRequest;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.routing.util.EdgeFilter;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphPrecisionSnapTest {
    @Test void projectionNoiseAtEitherEndpointUsesTheExistingNode() {
        checkEndpoint(false);
        checkEndpoint(true);
    }

    private void checkEndpoint(boolean reversed) {
        var manager = EncodingManager.create(new CarFlagEncoder());
        try (var graph = new GraphBuilder(manager).create()) {
            graph.getNodeAccess().setNode(0,52.2756752,21.0543821);
            graph.getNodeAccess().setNode(1,52.2745361,21.0531883);
            graph.getNodeAccess().setNode(2,52.276,21.054);
            graph.getNodeAccess().setNode(3,52.275,21.055);
            graph.edge(0,2); graph.edge(0,3);
            var edge = reversed ? graph.edge(1,0) : graph.edge(0,1);
            var snap = new Snap(52.27567519909787,21.0543821);
            snap.setClosestNode(0);
            snap.setClosestEdge(edge);
            snap.setWayIndex(0);
            snap.setSnappedPosition(Snap.Position.EDGE);
            snap.calcSnappedPoint(DistanceCalcEarth.DIST_EARTH);
            assertEquals(Snap.Position.EDGE,snap.getSnappedPosition());
            var result = GraphPrecisionSnap.normalize(snap, graph);
            assertEquals(Snap.Position.TOWER,result.getSnappedPosition());
            assertEquals(0,result.getClosestNode());
            assertEquals(graph.getNodeAccess().getLat(0),result.getSnappedPoint().lat);
            assertEquals(graph.getNodeAccess().getLon(0),result.getSnappedPoint().lon);
            assertSame(result,GraphPrecisionSnap.normalize(result, graph));
        }
    }

    @Test void distinctPointOnTheEdgeRemainsVirtual() {
        var manager = EncodingManager.create(new CarFlagEncoder());
        try (var graph = new GraphBuilder(manager).create()) {
            graph.getNodeAccess().setNode(0,52.2756752,21.0543821);
            graph.getNodeAccess().setNode(1,52.2745361,21.0531883);
            graph.getNodeAccess().setNode(2,52.276,21.054);
            graph.getNodeAccess().setNode(3,52.275,21.055);
            graph.edge(0,2); graph.edge(0,3);
            var snap = new Snap(52.27566,21.05437);
            snap.setClosestNode(0);
            snap.setClosestEdge(graph.edge(0,1));
            snap.setWayIndex(0);
            snap.setSnappedPosition(Snap.Position.EDGE);
            snap.calcSnappedPoint(DistanceCalcEarth.DIST_EARTH);
            assertSame(snap,GraphPrecisionSnap.normalize(snap, graph));
            assertEquals(Snap.Position.EDGE,snap.getSnappedPosition());
        }
    }
    @Test void legacyRequestsKeepTheirOriginalSnap() {
        var manager = EncodingManager.create(new CarFlagEncoder());
        try (var graph = new GraphBuilder(manager).create()) {
            graph.getNodeAccess().setNode(0,52.2756752,21.0543821);
            graph.getNodeAccess().setNode(1,52.2745361,21.0531883);
            graph.getNodeAccess().setNode(2,52.276,21.054);
            graph.getNodeAccess().setNode(3,52.275,21.055);
            graph.edge(0,2); graph.edge(0,3);
            var snap = new Snap(52.27567519909787,21.0543821);
            snap.setClosestNode(0);
            snap.setClosestEdge(graph.edge(0,1));
            snap.setWayIndex(0);
            snap.setSnappedPosition(Snap.Position.EDGE);
            snap.calcSnappedPoint(DistanceCalcEarth.DIST_EARTH);
            LocationIndex delegate = mock(LocationIndex.class);
            when(delegate.findClosest(anyDouble(),anyDouble(),any())).thenReturn(snap);
            var index = new ContinuityLocationIndex(delegate, graph);
            var request = new GHRequest(52.27567519909787,21.0543821,52.2745361,21.0531883);
            try (var scope = ContinuityLocationIndex.begin(request)) {
                assertSame(snap,index.findClosest(request.getPoints().get(0).lat,request.getPoints().get(0).lon,EdgeFilter.ALL_EDGES));
            }
            request.putHint("ors.continuity_track",true);
            try (var scope = ContinuityLocationIndex.begin(request)) {
                assertEquals(Snap.Position.TOWER,index.findClosest(request.getPoints().get(0).lat,request.getPoints().get(0).lon,EdgeFilter.ALL_EDGES).getSnappedPosition());
            }
        }
    }

    @Test void aNonJunctionKeepsItsOriginalSnap() {
        var manager = EncodingManager.create(new CarFlagEncoder());
        try (var graph = new GraphBuilder(manager).create()) {
            graph.getNodeAccess().setNode(0,52.2756752,21.0543821);
            graph.getNodeAccess().setNode(1,52.2745361,21.0531883);
            graph.getNodeAccess().setNode(2,52.276,21.054);
            graph.edge(0,2);
            var snap = new Snap(52.27567519909787,21.0543821);
            snap.setClosestNode(0);
            snap.setClosestEdge(graph.edge(0,1));
            snap.setWayIndex(0);
            snap.setSnappedPosition(Snap.Position.EDGE);
            snap.calcSnappedPoint(DistanceCalcEarth.DIST_EARTH);
            assertSame(snap,GraphPrecisionSnap.normalize(snap,graph));
        }
    }

}

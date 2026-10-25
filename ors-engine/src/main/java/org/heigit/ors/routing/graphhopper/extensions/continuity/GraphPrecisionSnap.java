package org.heigit.ors.routing.graphhopper.extensions.continuity;

import com.graphhopper.storage.index.Snap;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Helper;

public final class GraphPrecisionSnap {
    private GraphPrecisionSnap() {}

    public static Snap normalize(Snap snap, Graph graph) {
        if (!snap.isValid() || snap.getSnappedPosition() == Snap.Position.TOWER) return snap;
        var edge = snap.getClosestEdge();
        var geometry = edge.fetchWayGeometry(FetchMode.ALL);
        var point = snap.getSnappedPoint();
        for (int index : new int[]{0, geometry.size() - 1}) {
            if (!sameCell(point.lat, geometry.getLat(index)) || !sameCell(point.lon, geometry.getLon(index))) continue;
            int node = index == 0 ? edge.getBaseNode() : edge.getAdjNode();
            var edges = graph.createEdgeExplorer().setBaseNode(node);
            int degree = 0;
            while (edges.next() && degree <= 2) degree++;
            if (degree <= 2) continue;
            // Only a junction loses a departure choice through projection noise.
            var result = new Snap(snap.getQueryPoint().lat, snap.getQueryPoint().lon);
            result.setClosestEdge(edge);
            result.setClosestNode(node);
            result.setWayIndex(index);
            result.setSnappedPosition(Snap.Position.TOWER);
            result.setQueryDistance(DistanceCalcEarth.DIST_EARTH.calcDist(
                    snap.getQueryPoint().lat, snap.getQueryPoint().lon, geometry.getLat(index), geometry.getLon(index)));
            result.calcSnappedPoint(DistanceCalcEarth.DIST_EARTH);
            return result;
        }
        return snap;
    }

    private static boolean sameCell(double a, double b) {
        double quantum = Helper.intToDegree(1);
        return Math.round(a / quantum) == Math.round(b / quantum);
    }
}

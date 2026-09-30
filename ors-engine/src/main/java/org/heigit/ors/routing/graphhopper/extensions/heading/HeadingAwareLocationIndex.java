package org.heigit.ors.routing.graphhopper.extensions.heading;

import com.graphhopper.GHRequest;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HeadingAwareLocationIndex implements LocationIndex {
    public static final String DEVIATIONS_HINT = "ors.heading_deviations";
    private final LocationIndex delegate;
    private final ThreadLocal<Context> current = new ThreadLocal<>();

    private static final class Context {
        final GHRequest request;
        final List<Map<String, Object>> diagnostics = new ArrayList<>();
        int cursor;
        Weighting weighting;

        Context(GHRequest request) {
            this.request = request;
        }
    }

    public final class Scope implements AutoCloseable {
        private final Context previous;
        private final Context context;

        private Scope(GHRequest request) {
            previous = current.get();
            context = new Context(request);
            current.set(context);
        }

        public List<Map<String, Object>> diagnostics() {
            return List.copyOf(context.diagnostics);
        }

        @Override
        public void close() {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    public HeadingAwareLocationIndex(LocationIndex delegate) {
        this.delegate = delegate;
    }

    public Scope begin(GHRequest request) {
        return new Scope(request);
    }

    public WeightingFactory captureWeighting(WeightingFactory factory) {
        return (profile, hints, disableTurnCosts) -> {
            Weighting weighting = factory.createWeighting(profile, hints, disableTurnCosts);
            Context context = current.get();
            if (context != null) context.weighting = weighting;
            return weighting;
        };
    }

    @Override
    public Snap findClosest(double lat, double lon, EdgeFilter filter) {
        Snap baseline = delegate.findClosest(lat, lon, filter);
        Context context = current.get();
        if (context == null || context.weighting == null || !baseline.isValid()) return baseline;
        List<GHPoint> points = context.request.getPoints();
        int pointIndex = context.cursor;
        if (pointIndex >= points.size()) return baseline;
        GHPoint point = points.get(pointIndex);
        if (point.lat != lat || point.lon != lon) return baseline;
        context.cursor++;
        List<Double> headings = context.request.getHeadings();
        if (headings.size() != points.size() || !Double.isFinite(headings.get(pointIndex))) return baseline;
        // A through point needs separate arrival and departure constraints.
        if (pointIndex != 0 && pointIndex != points.size() - 1) return baseline;
        double heading = headings.get(pointIndex);
        List<Double> deviations = context.request.getHints().getObject(DEVIATIONS_HINT, List.of());
        double deviation = deviations.size() == points.size() ? deviations.get(pointIndex) : 100;
        if (!Double.isFinite(deviation) || deviation < 0 || deviation > 180) return baseline;
        double[] radiuses = context.request.getMaxSearchDistances();
        double requestRadius = radiuses == null || radiuses.length <= pointIndex || radiuses[pointIndex] < 0
                ? Double.POSITIVE_INFINITY : radiuses[pointIndex];
        boolean arrival = pointIndex == points.size() - 1;
        HeadingSnapSelector.Candidate<Snap> original = candidate(baseline, heading, arrival, context.weighting);
        List<HeadingSnapSelector.Candidate<Snap>> candidates = new ArrayList<>();
        if (original.angleDiff() > deviation && requestRadius > 0) {
            double radius = Math.min(requestRadius, HeadingSnapSelector.ALTERNATIVE_RADIUS_M);
            Set<Integer> ids = new LinkedHashSet<>();
            // findCandidateSnaps stops at its first nonempty box, not at our radius.
            delegate.query(DistanceCalcEarth.DIST_EARTH.createBBox(lat, lon, radius), ids::add);
            for (int id : ids) {
                Snap snap = delegate.findClosest(lat, lon, edge -> edge.getEdge() == id && filter.accept(edge));
                if (snap.isValid() && snap.getQueryDistance() <= radius)
                    candidates.add(candidate(snap, heading, arrival, context.weighting));
            }
        }
        HeadingSnapSelector.Selection<Snap> choice = HeadingSnapSelector.select(original, candidates, deviation, requestRadius);
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("pointIndex", pointIndex);
        diagnostic.put("role", arrival ? "END" : "START");
        diagnostic.put("baselineEdgeId", original.edgeId());
        diagnostic.put("selectedEdgeId", choice.candidate().edgeId());
        diagnostic.put("baselineDistanceM", original.distanceM());
        diagnostic.put("selectedDistanceM", choice.candidate().distanceM());
        diagnostic.put("requestedBearing", heading);
        diagnostic.put("requestedDeviation", deviation);
        diagnostic.put("effectiveDeviation", choice.effectiveDeviation());
        diagnostic.put("baselineAngleDiff", finiteOrNull(original.angleDiff()));
        diagnostic.put("selectedAngleDiff", finiteOrNull(choice.candidate().angleDiff()));
        diagnostic.put("candidateCountWithin30M", candidates.size());
        diagnostic.put("decision", choice.decision());
        context.diagnostics.add(diagnostic);
        return choice.candidate().snap();
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static HeadingSnapSelector.Candidate<Snap> candidate(Snap snap, double heading,
                                                                 boolean arrival, Weighting weighting) {
        PointList geometry = snap.getClosestEdge().fetchWayGeometry(FetchMode.ALL);
        int wayIndex = snap.getWayIndex();
        boolean inside = snap.getSnappedPosition() == Snap.Position.EDGE;
        int right = inside ? wayIndex + 1 : wayIndex;
        int left = inside ? wayIndex : wayIndex - 1;
        GHPoint snapped = snap.getSnappedPoint();
        double diff = Double.POSITIVE_INFINITY;
        boolean forward = Double.isFinite(weighting.calcEdgeWeightWithAccess(snap.getClosestEdge(), false));
        boolean reverse = Double.isFinite(weighting.calcEdgeWeightWithAccess(snap.getClosestEdge(), true));
        if (forward) {
            diff = Math.min(diff, arrival
                    ? localDiff(geometry, left, -1, snapped, heading, true)
                    : localDiff(geometry, right, 1, snapped, heading, false));
        }
        if (reverse) {
            diff = Math.min(diff, arrival
                    ? localDiff(geometry, right, 1, snapped, heading, true)
                    : localDiff(geometry, left, -1, snapped, heading, false));
        }
        return new HeadingSnapSelector.Candidate<>(snap, snap.getClosestEdge().getEdge(), snap.getQueryDistance(), diff);
    }

    private static double localDiff(PointList geometry, int index, int step, GHPoint snapped,
                                    double heading, boolean arrival) {
        while (index >= 0 && index < geometry.size()) {
            double lat = geometry.getLat(index), lon = geometry.getLon(index);
            if (DistanceCalcEarth.DIST_EARTH.calcDist(lat, lon, snapped.lat, snapped.lon) > 0.01) {
                double bearing = arrival ? bearing(lat, lon, snapped.lat, snapped.lon)
                        : bearing(snapped.lat, snapped.lon, lat, lon);
                double diff = Math.abs(bearing - heading) % 360;
                return Math.min(diff, 360 - diff);
            }
            index += step;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2), d = Math.toRadians(lon2 - lon1);
        return (Math.toDegrees(Math.atan2(Math.sin(d) * Math.cos(b),
                Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(d))) + 360) % 360;
    }

    @Override
    public List<Snap> findCandidateSnaps(double lat, double lon, EdgeFilter filter) {
        return delegate.findCandidateSnaps(lat, lon, filter);
    }

    @Override
    public void query(BBox bounds, Visitor visitor) {
        delegate.query(bounds, visitor);
    }

    @Override
    public void close() {
        delegate.close();
    }
}

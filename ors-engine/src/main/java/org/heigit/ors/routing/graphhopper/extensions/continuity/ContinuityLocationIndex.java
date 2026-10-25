package org.heigit.ors.routing.graphhopper.extensions.continuity;

import com.graphhopper.GHRequest;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.shapes.BBox;
import java.util.List;

public final class ContinuityLocationIndex implements LocationIndex {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final class Context {
        final GHRequest request;
        int cursor;
        Context(GHRequest request) { this.request = request; }
    }
    private final LocationIndex delegate;
    private final Graph graph;

    public ContinuityLocationIndex(LocationIndex delegate, Graph graph) { this.delegate = delegate; this.graph = graph; }

    public static Scope begin(GHRequest request) { return new Scope(request); }

    public static final class Scope implements AutoCloseable {
        private final Context previous = CURRENT.get();
        private Scope(GHRequest request) { CURRENT.set(new Context(request)); }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    @Override public Snap findClosest(double lat, double lon, EdgeFilter filter) {
        Context context = CURRENT.get();
        if (context != null && context.cursor++ == 0) {
            GHRequest request = context.request;
            Integer key = request.getHints().getObject("ors.continuity_incoming", null);
            var start = request.getPoints().get(0);
            if (key != null && start.lat == lat && start.lon == lon)
                return normalizeForRequest(delegate.findClosest(lat, lon, edge -> edge.getEdge() == key / 2 && filter.accept(edge)), context);
        }
        return normalizeForRequest(delegate.findClosest(lat, lon, filter), context);
    }

    @Override public List<Snap> findCandidateSnaps(double lat, double lon, EdgeFilter filter) {
        return delegate.findCandidateSnaps(lat, lon, filter).stream().map(snap -> normalizeForRequest(snap, CURRENT.get())).toList();
    }
    private Snap normalizeForRequest(Snap snap, Context context) {
        return context != null && context.request.getHints().getBool("ors.continuity_track", false)
                ? GraphPrecisionSnap.normalize(snap, graph) : snap;
    }

    @Override public void query(BBox bounds, Visitor visitor) { delegate.query(bounds, visitor); }
    @Override public void close() { delegate.close(); }
}

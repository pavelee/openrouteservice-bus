package org.heigit.ors.routing.graphhopper.extensions.routequality;

import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;

import java.util.Set;

public final class GraphMarkerWeighting extends AbstractAdjustedWeighting {
    private final IntEncodedValue encoded;
    private final Set<Integer> selected;

    public GraphMarkerWeighting(Weighting original, IntEncodedValue encoded, Set<Integer> selected) {
        super(original);
        this.encoded = encoded;
        this.selected = Set.copyOf(selected);
    }

    private boolean allowed(EdgeIteratorState edge) {
        int token = edge.get(encoded);
        return token == 0 || selected.contains(token);
    }

    @Override public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse) {
        return allowed(edge) ? super.calcEdgeWeight(edge, reverse) : Double.POSITIVE_INFINITY;
    }

    @Override public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse, long enterTime) {
        return allowed(edge) ? super.calcEdgeWeight(edge, reverse, enterTime) : Double.POSITIVE_INFINITY;
    }

    @Override public String getName() { return superWeighting.getName(); }
}

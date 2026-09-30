package org.heigit.ors.routing.graphhopper.extensions.heading;

import java.util.Comparator;
import java.util.List;

public final class HeadingSnapSelector {
    public static final double ALTERNATIVE_RADIUS_M = 30;
    public static final double FALLBACK_DEVIATION = 60;

    private HeadingSnapSelector() {
    }

    public record Candidate<T>(T snap, int edgeId, double distanceM, double angleDiff) {
    }

    public record Selection<T>(Candidate<T> candidate, String decision, double effectiveDeviation) {
    }

    public static <T> Selection<T> select(Candidate<T> baseline, List<Candidate<T>> candidates,
                                         double deviation, double requestRadius) {
        if (baseline.angleDiff() <= deviation)
            return new Selection<>(baseline, "BASELINE_COMPATIBLE", deviation);
        double radius = Math.min(requestRadius, ALTERNATIVE_RADIUS_M);
        Comparator<Candidate<T>> order = Comparator.comparingDouble(Candidate<T>::distanceM)
                .thenComparingDouble(Candidate<T>::angleDiff).thenComparingInt(Candidate<T>::edgeId);
        Candidate<T> strict = candidates.stream()
                .filter(c -> c.distanceM() <= radius && c.angleDiff() <= deviation)
                .min(order).orElse(null);
        if (strict != null)
            return new Selection<>(strict, "STRICT", deviation);
        if (deviation < FALLBACK_DEVIATION) {
            Candidate<T> fallback = candidates.stream()
                    .filter(c -> c.distanceM() <= radius && c.angleDiff() <= FALLBACK_DEVIATION)
                    .min(order).orElse(null);
            if (fallback != null)
                return new Selection<>(fallback, "FALLBACK_60", FALLBACK_DEVIATION);
        }
        return new Selection<>(baseline, "BASELINE_NO_MATCH", deviation);
    }
}

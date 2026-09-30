package org.heigit.ors.routing.graphhopper.extensions.heading;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeadingSnapSelectorTest {
    private HeadingSnapSelector.Candidate<Integer> candidate(int id, double distance, double angle) {
        return new HeadingSnapSelector.Candidate<>(id, id, distance, angle);
    }

    @Test
    void compatibleBaselineAlwaysWins() {
        var baseline = candidate(1, 5, 30);
        var selected = HeadingSnapSelector.select(baseline, List.of(candidate(2, 1, 0)), 30, 400);
        assertSame(baseline, selected.candidate());
        assertEquals("BASELINE_COMPATIBLE", selected.decision());
    }

    @Test
    void strictSectorWinsOverNearerFallback() {
        var selected = HeadingSnapSelector.select(candidate(1, 0, 90),
                List.of(candidate(2, 2, 45), candidate(3, 20, 20)), 30, 400);
        assertEquals(3, selected.candidate().edgeId());
        assertEquals("STRICT", selected.decision());
    }

    @Test
    void fallbackDoesNotMisreportRequestedSector() {
        var selected = HeadingSnapSelector.select(candidate(1, 0, 90), List.of(candidate(2, 20, 47.4)), 30, 400);
        assertEquals("FALLBACK_60", selected.decision());
        assertEquals(60, selected.effectiveDeviation());
    }

    @Test
    void noMatchOrTooFarPreservesBaseline() {
        var baseline = candidate(1, 0, 90);
        assertSame(baseline, HeadingSnapSelector.select(baseline,
                List.of(candidate(2, 30.01, 0), candidate(3, 2, 60.01)), 30, 400).candidate());
        assertSame(baseline, HeadingSnapSelector.select(baseline, List.of(candidate(2, 20, 0)), 30, 10).candidate());
    }

    @Test
    void equalDistanceUsesAngleThenStableEdgeId() {
        var selected = HeadingSnapSelector.select(candidate(1, 0, 90),
                List.of(candidate(5, 10, 5), candidate(3, 10, 5), candidate(2, 10, 15)), 30, 400);
        assertEquals(3, selected.candidate().edgeId());
    }

    @Test
    void widerRequestedSectorIsNeverNarrowedByFallback() {
        var selected = HeadingSnapSelector.select(candidate(1, 0, 130), List.of(candidate(2, 5, 95)), 100, 400);
        assertEquals("STRICT", selected.decision());
        assertEquals(100, selected.effectiveDeviation());
    }
}

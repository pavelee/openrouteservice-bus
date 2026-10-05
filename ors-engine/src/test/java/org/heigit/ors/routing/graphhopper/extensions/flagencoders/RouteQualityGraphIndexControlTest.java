package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.PMap;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteQualityGraphIndexControlTest {
    @Test
    @DisplayName("Punkt na małym grafie bez znaczników i filtra ma poprawne przypięcie")
    void unmarkedGraphHasValidSnapWithoutFiltering() {
        EncodingManager em = new EncodingManager.Builder()
                .add(new ORSDefaultFlagEncoderFactory().createFlagEncoder(FlagEncoderNames.BUS, new PMap()))
                .build();
        try (var fixture = RouteQualityGraphTestFixture.parallelWays(em, way -> {})) {
            var snap = fixture.index().findClosest(52.23, 21.005, EdgeFilter.ALL_EDGES);
            assertTrue(snap.isValid());
            assertEquals(fixture.original().getEdge(), snap.getClosestEdge().getEdge());
        }
    }
}

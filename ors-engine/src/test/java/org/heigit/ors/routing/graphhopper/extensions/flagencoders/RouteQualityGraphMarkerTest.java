package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.json.Statement;
import com.graphhopper.routing.Dijkstra;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import org.heigit.ors.routing.graphhopper.extensions.ORSWeightingFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteQualityGraphMarkerTest {
    private final EncodingManager em = new EncodingManager.Builder()
            .add(new ORSDefaultFlagEncoderFactory().createFlagEncoder(FlagEncoderNames.BUS, new PMap()))
            .add(Subnetwork.create("bus_custom")).build();

    private Weighting weighting(GraphHopperStorage graph, int disabled) {
        CustomModel model = new CustomModel();
        model.addToPriority(Statement.If("bus$quality_variant == " + disabled, Statement.Op.MULTIPLY, 0.0));
        CustomProfile profile = new CustomProfile("bus_custom");
        profile.setVehicle(FlagEncoderNames.BUS).setTurnCosts(false);
        profile.setCustomModel(model);
        return new ORSWeightingFactory(graph, em).createWeighting(profile, new PMap(), false);
    }

    @Test
    @DisplayName("Nieaktywny wariant jest wykluczony z trasy i przypinania punktu w obu kierunkach")
    void zeroPriorityExcludesRoutingAndSnap() {
        IntEncodedValue variant = em.getIntEncodedValue("bus$quality_variant");
        try (var fixture = RouteQualityGraphTestFixture.parallelWays(em,
                way -> way.setTag("bus:quality_variant", Long.toString(way.getId())))) {
            GraphHopperStorage graph = fixture.graph();
            EdgeIteratorState original = fixture.original();
            EdgeIteratorState patched = fixture.patched();
            assertEquals(1, original.get(variant));
            assertEquals(2, patched.get(variant));
            for (int disabled : new int[]{1, 2}) {
                Weighting weighting = weighting(graph, disabled);
                EdgeIteratorState inactive = disabled == 1 ? original : patched;
                EdgeIteratorState active = disabled == 1 ? patched : original;
                assertFalse(new DefaultSnapFilter(weighting, em.getBooleanEncodedValue(Subnetwork.key("bus_custom"))).accept(inactive));
                assertTrue(new DefaultSnapFilter(weighting, em.getBooleanEncodedValue(Subnetwork.key("bus_custom"))).accept(active));
                assertTrue(new Dijkstra(graph, weighting, TraversalMode.NODE_BASED).calcPath(active.getBaseNode(), active.getAdjNode()).isFound());
                assertFalse(new Dijkstra(graph, weighting, TraversalMode.NODE_BASED).calcPath(inactive.getBaseNode(), inactive.getAdjNode()).isFound());
                assertFalse(new Dijkstra(graph, weighting, TraversalMode.NODE_BASED).calcPath(inactive.getAdjNode(), inactive.getBaseNode()).isFound());
                {
                    var snap = fixture.index().findClosest(52.23, 21.005,
                            new DefaultSnapFilter(weighting, em.getBooleanEncodedValue(Subnetwork.key("bus_custom"))));
                    assertTrue(snap.isValid());
                    assertEquals(active.getEdge(), snap.getClosestEdge().getEdge());
                }
            }
        }
    }
}

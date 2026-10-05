package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.json.Statement;
import com.graphhopper.routing.Dijkstra;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.TurnCost;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.util.parsers.OSMMaxSpeedParser;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PMap;
import com.graphhopper.util.FetchMode;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.routing.graphhopper.extensions.GraphProcessContext;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import org.heigit.ors.routing.graphhopper.extensions.ORSOSMReader;
import org.heigit.ors.routing.graphhopper.extensions.ORSWeightingFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteQualityGraphProducedTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path fixtures = Path.of("../script/test-fixtures");
    private record Result(boolean found, String points, double distance, long time, double weight) {}
    private record Imported(GraphHopperStorage graph, EncodingManager em, LocationIndexTree index) implements AutoCloseable {
        @Override public void close() { index.close(); graph.close(); }
    }

    private Imported read(Path input) throws Exception {
        var em = new EncodingManager.Builder()
                .add(new ORSDefaultFlagEncoderFactory().createFlagEncoder(FlagEncoderNames.BUS, new PMap().putObject("turn_costs", true)))
                .add(Subnetwork.create("bus_custom")).add(new OSMMaxSpeedParser()).build();
        var graph = new GraphBuilder(em).withTurnCosts(true).build();
        var reader = new ORSOSMReader(graph, new GraphProcessContext(new ProfileProperties()));
        reader.setFile(input.toFile());
        reader.setWayPointMaxDistance(1);
        reader.readGraph();
        return new Imported(graph, em, RouteQualityGraphTestFixture.preparedIndex(graph));
    }

    private Weighting weighting(Imported imported, JsonNode variants, Set<Integer> active) {
        var model = new CustomModel().setDistanceInfluence(700);
        if (variants != null) for (var variant : variants) {
            var enabled = integers(variant.get("activeInterventionIds"));
            boolean selected = true;
            for (var identity : variant.get("interventionIds"))
                if (active.contains(identity.asInt()) != enabled.contains(identity.asInt())) selected = false;
            if (!selected) model.addToPriority(Statement.If("bus$quality_variant == " + variant.get("token").asInt(), Statement.Op.MULTIPLY, 0.0));
        }
        var profile = new CustomProfile("bus_custom");
        profile.setVehicle(FlagEncoderNames.BUS).setTurnCosts(true);
        profile.setCustomModel(model);
        return new ORSWeightingFactory(imported.graph(), imported.em()).createWeighting(profile, new PMap(), false);
    }

    private Set<Integer> integers(JsonNode values) {
        Set<Integer> result = new HashSet<>();
        values.forEach(value -> result.add(value.asInt()));
        return result;
    }

    private Result route(Imported imported, Weighting weighting, double fromLat, double fromLon, double toLat, double toLon) {
        var filter = new DefaultSnapFilter(weighting, imported.em().getBooleanEncodedValue(Subnetwork.key("bus_custom")));
        var from = imported.index().findClosest(fromLat, fromLon, filter);
        var to = imported.index().findClosest(toLat, toLon, filter);
        assertTrue(from.isValid() && to.isValid());
        var query = QueryGraph.create(imported.graph(), from, to);
        var path = new Dijkstra(query, query.wrapWeighting(weighting), TraversalMode.EDGE_BASED).calcPath(from.getClosestNode(), to.getClosestNode());
        return new Result(path.isFound(), path.calcPoints().toString(), path.getDistance(), path.getTime(), path.getWeight());
    }

    private List<String> properties(Imported imported, Weighting weighting) {
        List<String> values = new ArrayList<>();
        var edges = imported.graph().getAllEdges();
        while (edges.next()) for (boolean reverse : new boolean[]{false, true}) {
            var edge = edges.detach(reverse);
            if (!Double.isFinite(weighting.calcEdgeWeight(edge, false))) continue;
            StringBuilder signature = new StringBuilder(edge.fetchWayGeometry(FetchMode.ALL).toString())
                    .append(" distance=").append(edge.getDistance());
            for (var encoded : imported.em().getEncodedValues()) {
                if (encoded.getName().equals("bus$quality_variant") || encoded.getName().equals(TurnCost.key(FlagEncoderNames.BUS))) continue;
                signature.append(" ").append(encoded.getName()).append("=");
                if (encoded instanceof BooleanEncodedValue value) signature.append(edge.get(value));
                else if (encoded instanceof DecimalEncodedValue value) signature.append(edge.get(value));
                else if (encoded instanceof IntEncodedValue value) signature.append(edge.get(value));
                else throw new AssertionError("Unsupported edge property " + encoded.getName());
            }
            values.add(signature.toString());
        }
        Collections.sort(values);
        return values;
    }

    @Test
    @DisplayName("Wszystkie kombinacje wyprodukowanej mapy zachowują trasy i koszt klasycznej transformacji")
    void generatedVariantsMatchClassicTransformations() throws Exception {
        var policy = mapper.readTree(fixtures.resolve("k9-small-marker-policy.json").toFile());
        var frozen = mapper.readTree(fixtures.resolve("k9-small-marker-baselines.json").toFile());
        var references = frozen.get("maps");
        assertEquals(64, references.size());
        try (var marked = read(fixtures.resolve("k9-small-marker-produced.xml"))) {
            for (var reference : references) {
                var active = integers(reference.get("activeInterventionIds"));
                var plain = temporary.resolve("ordinary.osm");
                StringBuilder xml = new StringBuilder();
                reference.get("xmlFragmentIds").forEach(index -> xml.append(frozen.get("fragments").get(index.asInt()).asText()));
                Files.writeString(plain, xml);
                try (var ordinary = read(plain)) {
                    var markedWeight = weighting(marked, policy.get("variants"), active);
                    var plainWeight = weighting(ordinary, null, active);
                    assertEquals(properties(ordinary, plainWeight), properties(marked, markedWeight), "edge properties active=" + active);
                    for (double lat : new double[]{52.23, 52.24, 52.25, 52.26, 52.28, 52.29}) {
                        for (boolean reverse : new boolean[]{false, true}) {
                            double from = reverse ? 21.01 : 21.0, to = reverse ? 21.0 : 21.01;
                            var expected = route(ordinary, plainWeight, lat, from, lat, to);
                            var actual = route(marked, markedWeight, lat, from, lat, to);
                            assertEquals(expected, actual, "active=" + active + " lat=" + lat + " reverse=" + reverse);
                            if (lat == 52.23 || lat == 52.26 || lat == 52.28 || lat == 52.29) assertTrue(actual.found());
                            if (lat == 52.25) assertEquals(active.contains(13), actual.found());
                            if (lat == 52.24) assertEquals(reverse == active.contains(11), actual.found());
                        }
                    }
                    assertTrue(route(ordinary, plainWeight, 52.28, 21, 52.285, 21.01).found());
                    assertEquals(route(ordinary, plainWeight, 52.28, 21, 52.285, 21.01), route(marked, markedWeight, 52.28, 21, 52.285, 21.01));
                    assertFalse(route(marked, markedWeight, 52.28, 21, 52.285, 21.005).found());
                    assertEquals(route(ordinary, plainWeight, 52.28, 21, 52.285, 21.005), route(marked, markedWeight, 52.28, 21, 52.285, 21.005));
                }
            }
        }
    }
}

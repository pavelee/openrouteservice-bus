package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.EdgeIteratorState;
import java.util.function.Consumer;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.heigit.ors.routing.graphhopper.extensions.routequality.GraphMarkerPolicy;

import static org.junit.jupiter.api.Assertions.assertTrue;

record RouteQualityGraphTestFixture(GraphHopperStorage graph, LocationIndexTree index,
                                    EdgeIteratorState original, EdgeIteratorState patched) implements AutoCloseable {
    static RouteQualityGraphTestFixture parallelWays(EncodingManager em, Consumer<ReaderWay> tags) {
        GraphHopperStorage graph = new GraphBuilder(em).create();
        graph.getNodeAccess().setNode(0, 52.23, 21.00);
        graph.getNodeAccess().setNode(1, 52.23, 21.01);
        graph.getNodeAccess().setNode(2, 52.2305, 21.00);
        graph.getNodeAccess().setNode(3, 52.2305, 21.01);
        EdgeIteratorState original = edge(graph, em, 0, tags);
        EdgeIteratorState patched = edge(graph, em, 2, tags);
        LocationIndexTree index = preparedIndex(graph);
        return new RouteQualityGraphTestFixture(graph, index, original, patched);
    }

    static void installTestPolicy(GraphHopperStorage graph, int pairs) throws Exception {
        var body = new TreeMap<String, Object>();
        body.put("schema", "route-quality-graph-marker-policy-v1");
        body.put("setVersion", "rq-graph-v2:" + "0".repeat(64));
        body.put("snapshotSha256", "0".repeat(64));
        body.put("encodingRequestSha256", "0".repeat(64));
        body.put("flagEncoderOptions", "turn_costs=true");
        body.put("encoder", graph.getEncodingManager().toFlagEncodersAsString());
        body.put("officialInterventionIds", List.of());
        body.put("qualityInterventionIds", java.util.stream.IntStream.rangeClosed(1, pairs).boxed().toList());
        body.put("rebuildOnlyInterventionIds", List.of());
        List<Map<String, Object>> variants = new ArrayList<>();
        for (int group = 1; group <= pairs; group++) for (int state = 0; state < 2; state++) {
            var variant = new TreeMap<String, Object>();
            variant.put("token", (group - 1) * 2 + state + 1);
            variant.put("interventionIds", List.of(group));
            variant.put("activeInterventionIds", state == 0 ? List.of() : List.of(group));
            variants.add(variant);
        }
        body.put("variants", variants);
        var mapper = new ObjectMapper();
        body.put("policySha256", GraphMarkerPolicy.digest(mapper.writeValueAsBytes(body)));
        graph.getProperties().put(GraphMarkerPolicy.PROPERTY, mapper.writeValueAsString(body));
    }

    static LocationIndexTree preparedIndex(GraphHopperStorage graph) {
        // The index captures bounds at construction.
        LocationIndexTree index = new LocationIndexTree(graph, graph.getDirectory());
        index.setMinResolutionInMeter(500).setMaxRegionSearch(8);
        index.prepareIndex();
        return index;
    }

    private static EdgeIteratorState edge(GraphHopperStorage graph, EncodingManager em, int from,
                                          Consumer<ReaderWay> tags) {
        ReaderWay way = new ReaderWay(from == 0 ? 1L : 2L);
        way.setTag("highway", "residential");
        tags.accept(way);
        EncodingManager.AcceptWay accepted = new EncodingManager.AcceptWay();
        assertTrue(em.acceptWay(way, accepted));
        return graph.edge(from, from + 1).setDistance(100)
                .setFlags(em.handleWayTags(way, accepted, em.createRelationFlags()));
    }

    @Override
    public void close() {
        index.close();
        graph.close();
    }
}

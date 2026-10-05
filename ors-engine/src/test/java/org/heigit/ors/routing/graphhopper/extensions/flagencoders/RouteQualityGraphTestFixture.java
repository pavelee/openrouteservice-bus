package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.EdgeIteratorState;
import java.util.function.Consumer;

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

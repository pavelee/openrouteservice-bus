package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.json.Statement;
import com.graphhopper.routing.Dijkstra;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PMap;
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

import static org.junit.jupiter.api.Assertions.*;

class RouteQualityGraphTopologyTest {
    @TempDir Path temporary;
    private record Result(String points, double distance, long time, double weight, int edges) {}

    private Result route(boolean duplicate, boolean clonePillars) throws Exception {
        String nodes = """
                <node id="1" lat="52.30" lon="21.00"/>
                <node id="2" lat="52.30" lon="21.003"/>
                <node id="3" lat="52.30" lon="21.006"/>
                <node id="4" lat="52.30" lon="21.01"/>
                """;
        if (clonePillars) nodes += """
                <node id="12" lat="52.30" lon="21.003"/>
                <node id="13" lat="52.30" lon="21.006"/>
                """;
        String tags = "<tag k=\"highway\" v=\"residential\"/><tag k=\"maxspeed\" v=\"30\"/>";
        String way = "<way id=\"101\"><nd ref=\"1\"/><nd ref=\"2\"/><nd ref=\"3\"/><nd ref=\"4\"/>"
                + tags + (duplicate ? "<tag k=\"bus:quality_variant\" v=\"1\"/>" : "") + "</way>";
        if (duplicate) way += "<way id=\"102\"><nd ref=\"1\"/><nd ref=\"" + (clonePillars ? 12 : 2)
                + "\"/><nd ref=\"" + (clonePillars ? 13 : 3) + "\"/><nd ref=\"4\"/>"
                + tags + "<tag k=\"bus:quality_variant\" v=\"2\"/></way>";
        return calculate("<osm version=\"0.6\">" + nodes + way + "</osm>", duplicate ? 1 : 0);
    }

    private Result calculate(String inputXml, int disabled) throws Exception {
        Path input = temporary.resolve("topology.osm");
        Files.writeString(input, inputXml);
        EncodingManager em = new EncodingManager.Builder()
                .add(new ORSDefaultFlagEncoderFactory().createFlagEncoder(FlagEncoderNames.BUS, new PMap())).build();
        try (GraphHopperStorage graph = new GraphBuilder(em).build()) {
            var reader = new ORSOSMReader(graph, new GraphProcessContext(new ProfileProperties()));
            reader.setFile(input.toFile());
            reader.setWayPointMaxDistance(1);
            reader.readGraph();
            CustomModel model = new CustomModel().setDistanceInfluence(700);
            if (disabled > 0) model.addToPriority(Statement.If("bus$quality_variant == " + disabled, Statement.Op.MULTIPLY, 0.0));
            CustomProfile profile = new CustomProfile("bus_custom");
            profile.setVehicle(FlagEncoderNames.BUS).setTurnCosts(false);
            profile.setCustomModel(model);
            var weighting = new ORSWeightingFactory(graph, em).createWeighting(profile, new PMap(), false);
            int start = -1, end = -1;
            for (int i = 0; i < graph.getNodes(); i++) {
                if (Math.abs(graph.getNodeAccess().getLon(i) - 21.00) < 0.000001) start = i;
                if (Math.abs(graph.getNodeAccess().getLon(i) - 21.01) < 0.000001) end = i;
            }
            assertTrue(start >= 0 && end >= 0);
            var path = new Dijkstra(graph, weighting, TraversalMode.NODE_BASED).calcPath(start, end);
            assertTrue(path.isFound());
            return new Result(path.calcPoints().toString(), path.getDistance(), path.getTime(), path.getWeight(), graph.getEdges());
        }
    }

    @Test
    @DisplayName("Współdzielone węzły pośrednie kopii drogi ujawniają zmianę jej podziału")
    void sharedPillarsExposeGeometryChange() throws Exception {
        var ordinary = route(false, false);
        var shared = route(true, false);
        assertNotEquals(ordinary.points(), shared.points());
        assertTrue(shared.edges() > ordinary.edges() * 2);
    }

    @Test
    @DisplayName("Osobne węzły pośrednie kopii zachowują geometrię i cały koszt zwykłej drogi")
    void clonedPillarsPreserveGeometryAndCost() throws Exception {
        var ordinary = route(false, false);
        var marked = route(true, true);
        assertEquals(ordinary.points(), marked.points());
        assertEquals(ordinary.distance(), marked.distance());
        assertEquals(ordinary.time(), marked.time());
        assertEquals(ordinary.weight(), marked.weight());
        assertEquals(ordinary.edges() * 2, marked.edges());
    }
    private Result sideRoad(boolean blocked, boolean marked, boolean isolateJunction) throws Exception {
        String nodes = """
                <node id="1" lat="52.30" lon="21.00"/>
                <node id="2" lat="52.30" lon="21.003"/>
                <node id="3" lat="52.30" lon="21.006"/>
                <node id="4" lat="52.30" lon="21.01"/>
                <node id="5" lat="52.301" lon="21.003"/>
                """;
        String mainTags = "<tag k=\"highway\" v=\"residential\"/><tag k=\"maxspeed\" v=\"30\"/>";
        String ways = "<way id=\"101\"><nd ref=\"1\"/><nd ref=\"2\"/><nd ref=\"3\"/><nd ref=\"4\"/>"
                + mainTags + (marked && isolateJunction ? "<tag k=\"bus:quality_variant\" v=\"1\"/>" : "") + "</way>";
        if (marked && isolateJunction) {
            nodes += """
                    <node id="32" lat="52.30" lon="21.003"/>
                    <node id="33" lat="52.30" lon="21.006"/>
                    """;
            ways += "<way id=\"102\"><nd ref=\"1\"/><nd ref=\"32\"/><nd ref=\"33\"/><nd ref=\"4\"/>"
                    + mainTags + "<tag k=\"bus:quality_variant\" v=\"2\"/></way>";
        }
        String sideHighway = !marked && blocked ? "construction" : "residential";
        ways += "<way id=\"103\"><nd ref=\"2\"/><nd ref=\"5\"/><tag k=\"highway\" v=\"" + sideHighway
                + "\"/>" + (marked ? "<tag k=\"bus:quality_variant\" v=\"1\"/>" : "") + "</way>";
        if (marked) ways += "<way id=\"104\"><nd ref=\"" + (isolateJunction ? 32 : 2)
                + "\"/><nd ref=\"5\"/><tag k=\"highway\" v=\"construction\"/>"
                + "<tag k=\"bus:quality_variant\" v=\"2\"/></way>";
        return calculate("<osm version=\"0.6\">" + nodes + ways + "</osm>", marked ? (blocked ? 1 : 2) : 0);
    }

    @Test
    @DisplayName("Wyłączona droga boczna nie powinna dzielić geometrii drogi głównej")
    void inactiveSideRoadNeedsJunctionIsolation() throws Exception {
        assertNotEquals(sideRoad(true, false, false).points(), sideRoad(true, true, false).points());
        for (boolean blocked : new boolean[]{false, true}) {
            var ordinary = sideRoad(blocked, false, false);
            var marked = sideRoad(blocked, true, true);
            assertEquals(ordinary.points(), marked.points());
            assertEquals(ordinary.distance(), marked.distance());
            assertEquals(ordinary.time(), marked.time());
            assertEquals(ordinary.weight(), marked.weight());
        }
    }

}

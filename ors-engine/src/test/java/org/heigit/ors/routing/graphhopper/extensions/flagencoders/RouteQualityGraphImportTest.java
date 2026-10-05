package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.json.Statement;
import com.graphhopper.routing.ev.MaxSpeed;
import com.graphhopper.routing.util.parsers.OSMMaxSpeedParser;
import com.graphhopper.routing.Dijkstra;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PMap;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.routing.graphhopper.extensions.GraphProcessContext;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import org.heigit.ors.routing.graphhopper.extensions.ORSOSMReader;
import org.heigit.ors.routing.graphhopper.extensions.ORSWeightingFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteQualityGraphImportTest {
    @TempDir Path temporary;
    private EncodingManager em;
    private GraphHopperStorage graph;
    private LocationIndexTree index;

    @BeforeEach
    void importVariants() throws Exception {
        em = new EncodingManager.Builder()
                .add(new ORSDefaultFlagEncoderFactory().createFlagEncoder(FlagEncoderNames.BUS,
                        new PMap().putObject("turn_costs", true)))
                .add(Subnetwork.create("bus_custom"))
                .add(new OSMMaxSpeedParser()).build();
        graph = new GraphBuilder(em).withTurnCosts(true).build();
        Path input = temporary.resolve("variants.osm");
        Files.writeString(input, FIXTURE);
        var reader = new ORSOSMReader(graph, new GraphProcessContext(new ProfileProperties()));
        reader.setFile(input.toFile());
        reader.readGraph();
        index = RouteQualityGraphTestFixture.preparedIndex(graph);
    }

    @AfterEach
    void closeGraph() {
        if (index != null) index.close();
        if (graph != null) graph.close();
    }

    private Weighting select(Set<Integer> active) {
        CustomModel model = new CustomModel();
        for (int variant = 1; variant <= 10; variant++)
            if (!active.contains(variant))
                model.addToPriority(Statement.If("bus$quality_variant == " + variant, Statement.Op.MULTIPLY, 0.0));
        CustomProfile profile = new CustomProfile("bus_custom");
        profile.setVehicle(FlagEncoderNames.BUS).setTurnCosts(true);
        profile.setCustomModel(model);
        return new ORSWeightingFactory(graph, em).createWeighting(profile, new PMap(), false);
    }

    private int node(double lat, double lon) {
        for (int i = 0; i < graph.getNodes(); i++)
            if (Math.abs(graph.getNodeAccess().getLat(i) - lat) < 0.000001
                    && Math.abs(graph.getNodeAccess().getLon(i) - lon) < 0.000001) return i;
        throw new AssertionError("Missing fixture node");
    }

    private boolean route(double lat, double fromLon, double toLon, Set<Integer> active) {
        return new Dijkstra(graph, select(active), TraversalMode.EDGE_BASED)
                .calcPath(node(lat, fromLon), node(lat, toLon)).isFound();
    }

    @Test
    @DisplayName("Zmiana kierunku wybiera oryginalny albo poprawiony przejazd po imporcie")
    void onewayVariantsRetainTheirDirections() {
        assertTrue(route(52.23, 21.00, 21.01, Set.of(1)));
        assertFalse(route(52.23, 21.01, 21.00, Set.of(1)));
        assertFalse(route(52.23, 21.00, 21.01, Set.of(2)));
        assertTrue(route(52.23, 21.01, 21.00, Set.of(2)));
    }

    @Test
    @DisplayName("Szlaban blokuje oryginał i przepuszcza wariant z uprawnieniem autobusu")
    void clonedBarrierRetainsOriginalAccess() {
        assertFalse(route(52.24, 21.00, 21.01, Set.of(3)));
        assertFalse(route(52.24, 21.01, 21.00, Set.of(3)));
        assertTrue(route(52.24, 21.00, 21.01, Set.of(4)));
        assertTrue(route(52.24, 21.01, 21.00, Set.of(4)));
    }

    @Test
    @DisplayName("Zakaz skrętu obowiązuje dla oryginalnego i poprawionego wariantu drogi")
    void duplicatedRestrictionsCoverEachVariant() {
        int from = node(52.25, 21.00), to = node(52.255, 21.005);
        for (int variant : new int[]{5, 6})
            assertFalse(new Dijkstra(graph, select(Set.of(variant)), TraversalMode.EDGE_BASED)
                    .calcPath(from, to).isFound());
        assertTrue(new Dijkstra(graph, select(Set.of(5)), TraversalMode.EDGE_BASED)
                .calcPath(to, from).isFound());
    }

    @Test
    @DisplayName("Dwie nakładające się poprawki zachowują wszystkie kombinacje kierunku i prędkości")
    void overlappingVariantsRetainIndependentProperties() throws Exception {
        IntEncodedValue variant = em.getIntEncodedValue("bus$quality_variant");
        DecimalEncodedValue speed = em.getEncoder(FlagEncoderNames.BUS).getAverageSpeedEnc();
        DecimalEncodedValue maxSpeed = em.getDecimalEncodedValue(MaxSpeed.KEY);
        for (int selected : new int[]{7, 8, 9, 10}) {
            boolean forward = selected == 7 || selected == 9;
            assertEquals(forward, route(52.26, 21.00, 21.01, Set.of(selected)));
            assertEquals(!forward, route(52.26, 21.01, 21.00, Set.of(selected)));
            var expected = referenceFlags(selected);
            var edges = graph.getAllEdges();
            int count = 0;
            while (edges.next()) if (edges.get(variant) == selected) {
                assertEquals(speed.getDecimal(false, expected), edges.get(speed));
                assertEquals(speed.getDecimal(true, expected), edges.getReverse(speed));
                assertEquals(maxSpeed.getDecimal(false, expected), edges.get(maxSpeed));
                assertEquals(maxSpeed.getDecimal(true, expected), edges.getReverse(maxSpeed));
                count++;
            }
            assertEquals(1, count);
        }
    }

    private IntsRef referenceFlags(int selected) {
        var edges = graph.getAllEdges();
        while (edges.next()) if (edges.getName().equals("reference-" + selected)) {
            var flags = IntsRef.deepCopyOf(edges.getFlags());
            assertEquals(0, em.getIntEncodedValue("bus$quality_variant").getInt(false, flags));
            return flags;
        }
        throw new AssertionError("Missing ordinary reference way");
    }

    @Test
    @DisplayName("Oficjalny objazd pozostaje przejezdny przy wyłączeniu wszystkich poprawek jakości")
    void officialDetourIsAlwaysActive() {
        assertTrue(route(52.27, 21.00, 21.01, Set.of()));
        assertTrue(route(52.27, 21.01, 21.00, Set.of()));
        assertTrue(route(52.27, 21.00, 21.01, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
    }

    private static final String FIXTURE = """
<?xml version="1.0" encoding="UTF-8"?><osm version="0.6">
<node id="1" lat="52.23" lon="21"></node>
<node id="2" lat="52.23" lon="21.01"></node>
<node id="3" lat="52.24" lon="21"></node>
<node id="4" lat="52.24" lon="21.005"><tag k="barrier" v="lift_gate"/><tag k="access" v="private"/></node>
<node id="5" lat="52.24" lon="21.01"></node>
<node id="14" lat="52.24" lon="21.005"><tag k="barrier" v="lift_gate"/><tag k="access" v="private"/><tag k="psv" v="yes"/></node>
<node id="6" lat="52.25" lon="21"></node>
<node id="7" lat="52.25" lon="21.005"></node>
<node id="8" lat="52.255" lon="21.005"></node>
<node id="9" lat="52.26" lon="21"></node>
<node id="10" lat="52.26" lon="21.01"></node>
<node id="11" lat="52.27" lon="21"></node>
<node id="12" lat="52.27" lon="21.01"></node>
<node id="2014" lat="52.26" lon="21.0"/>
<node id="2015" lat="52.26" lon="21.01"/>
<node id="2016" lat="52.26" lon="21.0"/>
<node id="2017" lat="52.26" lon="21.01"/>
<node id="2018" lat="52.26" lon="21.0"/>
<node id="2019" lat="52.26" lon="21.01"/>
<node id="2020" lat="52.26" lon="21.0"/>
<node id="2021" lat="52.26" lon="21.01"/>
<way id="101"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="bus:quality_variant" v="1"/></way>
<way id="102"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="oneway" v="-1"/><tag k="bus:quality_variant" v="2"/></way>
<way id="103"><nd ref="3"/><nd ref="4"/><nd ref="5"/><tag k="highway" v="residential"/><tag k="bus:quality_variant" v="3"/></way>
<way id="104"><nd ref="3"/><nd ref="14"/><nd ref="5"/><tag k="highway" v="residential"/><tag k="bus:quality_variant" v="4"/></way>
<way id="105"><nd ref="6"/><nd ref="7"/><tag k="highway" v="residential"/><tag k="bus:quality_variant" v="5"/></way>
<way id="106"><nd ref="6"/><nd ref="7"/><tag k="highway" v="residential"/><tag k="bus:quality_variant" v="6"/></way>
<way id="107"><nd ref="7"/><nd ref="8"/><tag k="highway" v="residential"/></way>
<way id="108"><nd ref="9"/><nd ref="10"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="maxspeed" v="30"/><tag k="bus:quality_variant" v="7"/></way>
<way id="109"><nd ref="9"/><nd ref="10"/><tag k="highway" v="residential"/><tag k="oneway" v="-1"/><tag k="maxspeed" v="30"/><tag k="bus:quality_variant" v="8"/></way>
<way id="110"><nd ref="9"/><nd ref="10"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="maxspeed" v="50"/><tag k="bus:quality_variant" v="9"/></way>
<way id="111"><nd ref="9"/><nd ref="10"/><tag k="highway" v="residential"/><tag k="oneway" v="-1"/><tag k="maxspeed" v="50"/><tag k="bus:quality_variant" v="10"/></way>
<way id="112"><nd ref="11"/><nd ref="12"/><tag k="highway" v="residential"/><tag k="access" v="private"/><tag k="psv" v="yes"/></way>
<way id="2007"><nd ref="2014"/><nd ref="2015"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="maxspeed" v="30"/><tag k="name" v="reference-7"/></way>
<way id="2008"><nd ref="2016"/><nd ref="2017"/><tag k="highway" v="residential"/><tag k="oneway" v="-1"/><tag k="maxspeed" v="30"/><tag k="name" v="reference-8"/></way>
<way id="2009"><nd ref="2018"/><nd ref="2019"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="maxspeed" v="50"/><tag k="name" v="reference-9"/></way>
<way id="2010"><nd ref="2020"/><nd ref="2021"/><tag k="highway" v="residential"/><tag k="oneway" v="-1"/><tag k="maxspeed" v="50"/><tag k="name" v="reference-10"/></way>
<relation id="201"><member type="way" ref="105" role="from"/><member type="node" ref="7" role="via"/><member type="way" ref="107" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_left_turn"/></relation>
<relation id="202"><member type="way" ref="106" role="from"/><member type="node" ref="7" role="via"/><member type="way" ref="107" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_left_turn"/></relation>
</osm>
            """;
}

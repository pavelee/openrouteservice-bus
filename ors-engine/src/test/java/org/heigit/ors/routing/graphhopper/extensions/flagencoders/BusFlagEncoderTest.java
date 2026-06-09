package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.json.Statement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PMap;
import org.heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import org.heigit.ors.routing.graphhopper.extensions.ORSWeightingFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusFlagEncoderTest {
    private final EncodingManager em = EncodingManager.create(new ORSDefaultFlagEncoderFactory(), FlagEncoderNames.BUS);
    private final BusFlagEncoder encoder = (BusFlagEncoder) em.getEncoder(FlagEncoderNames.BUS);
    private ReaderWay way;

    @BeforeEach
    void initWay() {
        way = new ReaderWay(1);
    }

    @Test
    void testBasicAccess() {
        way.setTag("highway", "residential");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testAccessClosedToGeneralTraffic() {
        way.setTag("highway", "residential");
        way.setTag("access", "no");
        assertTrue(encoder.getAccess(way).canSkip());
    }

    @Test
    void testBusOnlyLinkIsAccessible() {
        // Droga zamknięta dla ruchu ogólnego, ale dopuszczona dla autobusu
        way.setTag("highway", "service");
        way.setTag("access", "no");
        way.setTag("bus", "yes");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testBusDesignatedAccess() {
        way.setTag("highway", "unclassified");
        way.setTag("motor_vehicle", "no");
        way.setTag("psv", "designated");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testDedicatedBuswayAccessibleWithoutGenericTags() {
        way.setTag("highway", "busway");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testParkingAisleSkipped() {
        way.setTag("highway", "service");
        way.setTag("service", "parking_aisle");
        assertTrue(encoder.getAccess(way).canSkip());
    }

    @Test
    void testTrackSkipped() {
        way.setTag("highway", "track");
        assertTrue(encoder.getAccess(way).canSkip());
    }

    @Test
    void testNarrowWayRejectedByPhysicalWidth() {
        way.setTag("highway", "residential");
        way.setTag("maxwidth", "2.0");
        assertTrue(encoder.getAccess(way).canSkip());
    }

    @Test
    void testWideEnoughWayAccepted() {
        way.setTag("highway", "residential");
        way.setTag("maxwidth", "3.0");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testLowBridgeRejectedByPhysicalHeight() {
        way.setTag("highway", "primary");
        way.setTag("maxheight", "2.5");
        assertTrue(encoder.getAccess(way).canSkip());
    }

    @Test
    void testWeightLimitDoesNotBlockBus() {
        // Miejskie limity tonażu (np. maxweight=5) celują w TIRy, nie w komunikację miejską.
        // Autobus musi pozostać dopuszczony (np. Aleje Jerozolimskie maxweight=5 + buspas).
        way.setTag("highway", "secondary");
        way.setTag("maxweight", "5");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testBusTrapBarrierPassable() {
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "bus_trap");
        assertTrue(encoder.handleNodeTags(node) == 0);
    }

    @Test
    void testBollardWithBusAccessPassable() {
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "bollard");
        node.setTag("bus", "yes");
        assertTrue(encoder.handleNodeTags(node) == 0);
    }

    @Test
    void testBollardWithoutBusAccessBlocks() {
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "bollard");
        assertFalse(encoder.handleNodeTags(node) == 0);
    }

    @Test
    void testBusPreferredEncodedValueForBuswayIsAvailableToCustomModel() {
        // EncodedValue "bus_preferred" musi istnieć (custom_model się do niego odwołuje)
        BooleanEncodedValue busPreferred = em.getBooleanEncodedValue(BusFlagEncoder.KEY_BUS_PREFERRED);

        way.setTag("highway", "bus_guideway");
        EncodingManager.AcceptWay acceptWay = new EncodingManager.AcceptWay();
        assertTrue(em.acceptWay(way, acceptWay));
        IntsRef edgeFlags = em.handleWayTags(way, acceptWay, em.createRelationFlags());
        assertTrue(busPreferred.getBool(false, edgeFlags));
    }

    @Test
    void testCustomModelReferencingBusPreferredCompiles() {
        // Odtwarza dokładnie ścieżkę runtime: ORSWeightingFactory -> CustomModelParser.
        // Jeśli zielony, identyfikator bus_preferred jest poprawny w custom_model (błąd 2018
        // w produkcji oznacza wtedy nieświeży graf/jar bez zarejestrowanego EncodedValue).
        GraphHopperStorage g = new GraphBuilder(em).create();
        ORSWeightingFactory weightingFactory = new ORSWeightingFactory(g, em);

        assertTrue(em.hasEncodedValue(BusFlagEncoder.KEY_BUS_PREFERRED));

        CustomModel customModel = new CustomModel();
        customModel.addToPriority(Statement.If("bus$preferred == true", Statement.Op.MULTIPLY, 1.0));
        customModel.addToPriority(Statement.Else(Statement.Op.MULTIPLY, 0.5));

        CustomProfile profile = new CustomProfile("bus_custom");
        profile.setVehicle(FlagEncoderNames.BUS);
        profile.setTurnCosts(false);
        profile.setCustomModel(customModel);

        Weighting weighting = weightingFactory.createWeighting(profile, new PMap(), false);
        assertNotNull(weighting);
    }

    @Test
    void testBusPreferredEncodedValueFalseForPlainResidential() {
        BooleanEncodedValue busPreferred = em.getBooleanEncodedValue(BusFlagEncoder.KEY_BUS_PREFERRED);

        way.setTag("highway", "residential");
        EncodingManager.AcceptWay acceptWay = new EncodingManager.AcceptWay();
        assertTrue(em.acceptWay(way, acceptWay));
        IntsRef edgeFlags = em.handleWayTags(way, acceptWay, em.createRelationFlags());
        assertFalse(busPreferred.getBool(false, edgeFlags));
    }
}

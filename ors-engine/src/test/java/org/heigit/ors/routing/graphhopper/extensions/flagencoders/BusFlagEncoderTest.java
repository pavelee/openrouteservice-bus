package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.json.Statement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void testDrivewaySkipped() {
        way.setTag("highway", "service");
        way.setTag("service", "driveway");
        assertTrue(encoder.getAccess(way).canSkip());
    }

    @Test
    void testDrivewayOnBusRouteAccessible() {
        // Linia 735 / kraniec Zegrze Płd. 03: wjazd na parking z przystankiem to service=driveway
        // bez żadnych tagów dostępu, ale way jest członkiem relacji OSM route=bus (bus:on_route=yes
        // wstrzykiwany build-time). Bez tego wyjątku przystanek był nieosiągalny w grafie.
        way.setTag("highway", "service");
        way.setTag("service", "driveway");
        way.setTag("bus:on_route", "yes");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testParkingAisleOnBusRouteAccessible() {
        // Ten sam przypadek co wyżej, wariant parking_aisle (pętla Palmiry 6401/71).
        way.setTag("highway", "service");
        way.setTag("service", "parking_aisle");
        way.setTag("bus:on_route", "yes");
        assertTrue(encoder.getAccess(way).isWay());
    }

    @Test
    void testDrivewayOnBusRouteStillNotBusPreferred() {
        // Kluczowy warunek bezpieczeństwa wyjątku bus:on_route: droga wchodzi do grafu, ale NIE
        // dostaje bus$preferred, więc reguła custom_model "SERVICE && bus$preferred == false → ×0.1"
        // dalej działa i router użyje jej tylko przy braku alternatywy.
        BooleanEncodedValue busPreferred = em.getBooleanEncodedValue(BusFlagEncoder.KEY_BUS_PREFERRED);

        way.setTag("highway", "service");
        way.setTag("service", "driveway");
        way.setTag("bus:on_route", "yes");
        EncodingManager.AcceptWay acceptWay = new EncodingManager.AcceptWay();
        assertTrue(em.acceptWay(way, acceptWay));
        IntsRef edgeFlags = em.handleWayTags(way, acceptWay, em.createRelationFlags());
        assertFalse(busPreferred.getBool(false, edgeFlags));
    }

    @Test
    void testDrivewayOnBusRouteStillRespectsAccessNo() {
        // Wyjątek dotyczy WYŁĄCZNIE reguły driveway/parking_aisle. Zakaz wjazdu (access=no bez
        // wyjątku dla autobusu) rozstrzyga się niżej w getAccess i musi dalej blokować.
        way.setTag("highway", "service");
        way.setTag("service", "driveway");
        way.setTag("bus:on_route", "yes");
        way.setTag("access", "no");
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
    void testLiftGatePassableEvenWhenBarriersBlocked() {
        // Rogatka/szlaban (np. automatyczna rogatka przejazdu kolejowego) musi być przejezdna dla
        // autobusu nawet gdy bariery są generalnie blokujące — to odwzorowuje stan produkcyjnego grafu,
        // w którym lift_gate przy PKP Legionowo Piaski odcinał linię 731.
        encoder.blockBarriers(true);
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "lift_gate");
        node.setTag("lift_gate:type", "double");
        assertTrue(encoder.handleNodeTags(node) == 0);
    }

    @Test
    void testLockedLiftGateBlocks() {
        encoder.blockBarriers(true);
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "lift_gate");
        node.setTag("locked", "yes");
        assertFalse(encoder.handleNodeTags(node) == 0);
    }

    @Test
    void testLiftGateWithAccessRestrictionBlocks() {
        encoder.blockBarriers(true);
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "lift_gate");
        node.setTag("access", "private");
        assertFalse(encoder.handleNodeTags(node) == 0);
    }

    @Test
    void testLiftGateWithBusAccessPassable() {
        encoder.blockBarriers(true);
        ReaderNode node = new ReaderNode(1, 0, 0);
        node.setTag("barrier", "lift_gate");
        node.setTag("access", "private");
        node.setTag("bus", "yes");
        assertTrue(encoder.handleNodeTags(node) == 0);
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

    @Test
    void testNodeDensityResidentialPenaltyNeutralized() {
        // Regres 2026-07-09 (linia 198, Głowackiego/PKP Wesoła): podział way'a w OSM —
        // bez zmiany geometrii ani tagów — aktywował odziedziczoną karę ×0.5 dla residential
        // ze średnim odstępem węzłów < 100 m (liczonym per way). Dla autobusu kara jest
        // zneutralizowana: prędkość nie może zależeć od tego, jak mapowicz potnie ulicę.
        way.setTag("highway", "residential");
        way.setTag("estimated_distance", 76.0); // 4 węzły na 76 m → ~25 m/węzeł (< 100)
        for (long i = 1; i <= 4; i++) way.getNodes().add(i);
        assertEquals(25.0, encoder.addResedentialPenalty(25.0, way), 0.001);
    }

    @Test
    void testResidentialSpeedIndependentOfWaySplit() {
        // Pełna ścieżka handleWayTags: ta sama ulica jako jeden długi way (rzadkie węzły
        // per way) i jako krótki kawałek po podziale (gęste węzły per way) musi dostać
        // IDENTYCZNĄ prędkość w grafie.
        DecimalEncodedValue avgSpeed = encoder.getAverageSpeedEnc();

        ReaderWay longWay = new ReaderWay(1);
        longWay.setTag("highway", "residential");
        longWay.setTag("estimated_distance", 313.0);
        for (long i = 1; i <= 4; i++) longWay.getNodes().add(i); // ~104 m/węzeł
        EncodingManager.AcceptWay acceptLong = new EncodingManager.AcceptWay();
        assertTrue(em.acceptWay(longWay, acceptLong));
        IntsRef longFlags = em.handleWayTags(longWay, acceptLong, em.createRelationFlags());

        ReaderWay splitWay = new ReaderWay(2);
        splitWay.setTag("highway", "residential");
        splitWay.setTag("estimated_distance", 76.0);
        for (long i = 1; i <= 4; i++) splitWay.getNodes().add(i); // ~25 m/węzeł
        EncodingManager.AcceptWay acceptSplit = new EncodingManager.AcceptWay();
        assertTrue(em.acceptWay(splitWay, acceptSplit));
        IntsRef splitFlags = em.handleWayTags(splitWay, acceptSplit, em.createRelationFlags());

        assertEquals(avgSpeed.getDecimal(false, longFlags), avgSpeed.getDecimal(false, splitFlags), 0.001);
    }
}

/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.ConditionalOSMSpeedInspector;
import com.graphhopper.reader.osm.conditional.ConditionalParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.ev.UnsignedDecimalEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.routing.weighting.PriorityWeighting;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import org.heigit.ors.routing.graphhopper.extensions.util.PriorityCode;

import java.util.*;

import static com.graphhopper.routing.util.EncodingManager.getKey;

/**
 * FlagEncoder szyty na miarę dla autobusu miejskiego (profil driving-bus).
 *
 * W odróżnieniu od {@link HeavyVehicleFlagEncoder} jest to czysta implementacja oparta wyłącznie
 * o surowe tagi OSM — bez semantyki TIRa (agricultural/forestry/goods/hgv) i bez helper-tagów
 * typu routing:ztm. Logika rozkłada się na trzy warstwy:
 *  (a) ten enkoder — dostęp/prędkość/jednokierunkowość zapieczone w grafie (build-time),
 *  (b) EncodedValue {@code bus_preferred} — atrybut per-krawędź czytany przez custom_model,
 *  (c) custom_model po stronie klienta — preferencje/priorytety (query-time, bez rebuildu).
 *
 * Preferencja buspasów realizowana jest WYŁĄCZNIE przez priorytet (EncodedValue bus_preferred +
 * reguła priority w custom_model), bez bonusu prędkości — GraphHopper odrzuca w custom_model
 * mnożnik speed > 1.0.
 */
public class BusFlagEncoder extends VehicleFlagEncoder {
    public static final String VAL_DESIGNATED = "designated";
    public static final String KEY_HIGHWAY = "highway";
    public static final String VAL_TRACK = "track";
    public static final String KEY_IMPASSABLE = "impassable";
    public static final String VAL_BUSWAY = "busway";
    public static final String VAL_BUS_GUIDEWAY = "bus_guideway";

    // Pełna nazwa EncodedValue widziana przez custom_model. EncodingManager WYMAGA, by EV
    // rejestrowany przez enkoder zawierał znak namespace '$' (getKey wstawia go: prefiks + '$' + suffix).
    // Klient w orsBusCustomModel.ts MUSI odwołać się dokładnie tą nazwą: `bus$preferred`.
    public static final String KEY_BUS_PREFERRED = FlagEncoderNames.BUS + "$preferred";

    // EncodedValue analogiczny do bus$preferred, ale sygnalizujący "ten way jest częścią
    // jakiejkolwiek relacji OSM route=bus" (tag bus:on_route=yes, wstrzykiwany build-time przez
    // fix_private_roads.py na podstawie PostGIS bus_route_ways — zob. cron/osm2pgsql/import/
    // bus_routes.lua). Zastępuje heurystykę "maxspeed otagowany ⇒ to nie skrót" w custom_model
    // realnym sygnałem "tu faktycznie jeździ jakaś linia autobusowa".
    public static final String KEY_BUS_ON_ROUTE = FlagEncoderNames.BUS + "$on_route";

    // Gabaryty fizyczne autobusu miejskiego (przegubowego) — krawędzie poniżej tych progów są
    // pomijane na podstawie surowych tagów OSM. Zmiana progu wymaga przebudowy grafu.
    // UWAGA: świadomie NIE sprawdzamy maxweight. Miejskie limity tonażu (maxweight=5/10 t) celują
    // w pojazdy ciężarowe i niemal zawsze zwalniają komunikację miejską (znak "nie dotyczy autobusów"),
    // ale OSM rzadko taguje ten wyjątek. Sprawdzanie maxweight blokowało autobus na arteriach, którymi
    // realnie jeździ (np. Aleje Jerozolimskie maxweight=5 + lanes:psv=1). maxheight/maxwidth/maxlength
    // to twarde gabaryty — autobus fizycznie się nie zmieści, więc je zostawiamy.
    private static final double BUS_MAX_WIDTH = 2.55;   // metry
    // Realna wysokość warszawskiego autobusu miejskiego (Solaris Urbino i pochodne z osprzętem
    // dachowym ≈ 3.0 m). NIE jest to "najwyższy możliwy autobus" — celowo niżej niż dawne 3.4 m.
    // Przy 3.4 m enkoder wycinał korytarz Żwirki i Wigury pod lotniskiem (maxheight=3.2 na tunelu
    // pod płytą), przez co przystanki Lotnisko-Przyloty / Terminal Autokarowy lądowały na
    // odizolowanej wyspie i trasa linii 331 nie dawała się wyznaczyć. Najniższe przejazdy, którymi
    // realnie kursują autobusy MZA, mają ~3.2 m, więc próg 3.0 m je przepuszcza, a nadal blokuje
    // konstrukcje faktycznie zbyt niskie (np. zadaszone dojazdy maxheight=2.5). Jawny wyjątek
    // maxheight:bus/maxheight:psv ma pierwszeństwo — patrz dimensionBelow().
    private static final double BUS_MAX_HEIGHT = 3.0;   // metry
    private static final double BUS_MAX_LENGTH = 18.75; // metry (max autobus przegubowy w UE)

    private static final int MEAN_SPEED = 50;

    protected final HashSet<String> forwardKeys = new HashSet<>(5);
    protected final HashSet<String> backwardKeys = new HashSet<>(5);
    // Tagi dostępu specyficzne dla autobusów / transportu publicznego
    protected final List<String> busAccess = new ArrayList<>(5);

    // Neutralny priorytet (BEST dla wszystkich krawędzi) — zachowany dla weightingu "recommended".
    private DecimalEncodedValue priorityWayEncoder;
    // Czy krawędź to dedykowana infrastruktura autobusowa — czytane przez custom_model.
    private BooleanEncodedValue busPreferredEncoder;
    // Czy krawędź jest częścią ≥1 relacji OSM route=bus — czytane przez custom_model, by
    // wyłączyć karę "osiedlowe skróty" dla legalnych korytarzy bez tagu maxspeed.
    private BooleanEncodedValue busOnRouteEncoder;

    /**
     * Should be only instantied via EncodingManager
     */
    public BusFlagEncoder() {
        this(5, 5, 0);
    }

    public BusFlagEncoder(PMap properties) {
        this(properties.getInt("speed_bits", 5),
                properties.getDouble("speed_factor", 5),
                properties.getBool("turn_costs", false) ? 3 : 0);

        setProperties(properties);

        maxTrackGradeLevel = properties.getInt("maximum_grade_level", 1);
    }

    public BusFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);

        maxPossibleSpeed = 90;

        intendedValues.add(VAL_DESIGNATED);
        intendedValues.add("bus");
        intendedValues.add("psv");
        intendedValues.add("public_transport");

        // Klucze restrykcyjne specyficzne dla transportu publicznego — DODANE PRZED odziedziczonymi
        // [motorcar, motor_vehicle, vehicle, access]. GraphHopper używa tej listy nie tylko do dostępu
        // krawędzi, ale też do dopasowania `except` w relacjach turn-restriction: profil jest zwolniony
        // z `except=X` tylko wtedy, gdy `X` jest na tej liście. Bez tego autobus NIE był zwalniany z
        // `except=bus`/`except=psv` (np. `no_left_turn except=bus` na Rondzie de Gaulle'a), więc tracił
        // dostęp do skrętów buspasowych i nadkładał drogi. Kolejność (bus/psv przed access) sprawia, że
        // dla drogi z bus=yes + access=no priorytet ma tag bus (getFirstPriorityTagValues).
        // UWAGA: zmiana jest build-time — wymaga przebudowy grafu.
        restrictions.add(0, "psv");
        restrictions.add(0, "bus");

        // Drogi zamknięte dla ruchu ogólnego — autobus korzysta z nich tylko przy jawnym wyjątku.
        restrictedValues.add("private");
        restrictedValues.add("no");
        restrictedValues.add("emergency");

        // Bus traps / słupki przepuszczające autobus
        passByDefaultBarriers.add("bus_trap");
        blockByDefaultBarriers.add("sump_buster");

        busAccess.addAll(Arrays.asList("bus", "psv", "public_transport"));

        // Mapa prędkości dla autobusu miejskiego (niższa niż car/HGV: przystanki, masa, promień skrętu).
        defaultSpeedMap.put("motorway", 70);
        defaultSpeedMap.put("motorway_link", 50);
        defaultSpeedMap.put("motorroad", 70);
        defaultSpeedMap.put("trunk", 65);
        defaultSpeedMap.put("trunk_link", 50);
        defaultSpeedMap.put("primary", 50);
        defaultSpeedMap.put("primary_link", 45);
        defaultSpeedMap.put("secondary", 45);
        defaultSpeedMap.put("secondary_link", 40);
        defaultSpeedMap.put("tertiary", 40);
        defaultSpeedMap.put("tertiary_link", 35);
        defaultSpeedMap.put("unclassified", 30);
        defaultSpeedMap.put("residential", 25);
        defaultSpeedMap.put("living_street", 8);
        defaultSpeedMap.put("service", 15);
        defaultSpeedMap.put("road", 20);
        defaultSpeedMap.put("track", 10);
        // Dedykowana infrastruktura autobusowa (poza domyślną mapą GH).
        defaultSpeedMap.put(VAL_BUSWAY, 50);
        defaultSpeedMap.put(VAL_BUS_GUIDEWAY, 50);

        initSpeedLimitHandler(this.toString());

        forwardKeys.add("bus:forward");
        forwardKeys.add("psv:forward");
        backwardKeys.add("bus:backward");
        backwardKeys.add("psv:backward");
    }

    @Override
    protected void init(DateRangeParser dateRangeParser) {
        super.init(dateRangeParser);
        ConditionalOSMSpeedInspector conditionalOSMSpeedInspector = new ConditionalOSMSpeedInspector(List.of("maxspeed"));
        conditionalOSMSpeedInspector.addValueParser(ConditionalParser.createDateTimeParser());
        setConditionalSpeedInspector(conditionalOSMSpeedInspector);
    }

    @Override
    public void createEncodedValues(List<EncodedValue> registerNewEncodedValue, String prefix, int index) {
        super.createEncodedValues(registerNewEncodedValue, prefix, index);
        priorityWayEncoder = new UnsignedDecimalEncodedValue(getKey(prefix, "priority"), 4, PriorityCode.getFactor(1), false);
        registerNewEncodedValue.add(priorityWayEncoder);
        // EncodedValue czytany przez custom_model jako "bus$preferred" (getKey wstawia '$' wymagany
        // przez EncodingManager dla EV enkodera).
        busPreferredEncoder = new SimpleBooleanEncodedValue(getKey(prefix, "preferred"), false);
        registerNewEncodedValue.add(busPreferredEncoder);
        // EncodedValue czytany przez custom_model jako "bus$on_route".
        busOnRouteEncoder = new SimpleBooleanEncodedValue(getKey(prefix, "on_route"), false);
        registerNewEncodedValue.add(busOnRouteEncoder);
    }

    @Override
    public double getMaxSpeed(ReaderWay way) {
        // Specjalny limit prędkości dla autobusów
        double maxSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus"));

        double fwdSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus:forward"));
        if (isValidSpeed(fwdSpeed) && (!isValidSpeed(maxSpeed) || fwdSpeed < maxSpeed)) {
            maxSpeed = fwdSpeed;
        }

        double backSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus:backward"));
        if (isValidSpeed(backSpeed) && (!isValidSpeed(maxSpeed) || backSpeed < maxSpeed)) {
            maxSpeed = backSpeed;
        }

        // Fallback na maxspeed:psv
        if (!isValidSpeed(maxSpeed)) {
            maxSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:psv"));
        }

        // Fallback na standardowy maxspeed (cap do prędkości domyślnej dla danej klasy drogi)
        if (!isValidSpeed(maxSpeed)) {
            maxSpeed = super.getMaxSpeed(way);
            if (isValidSpeed(maxSpeed)) {
                String highway = way.getTag(KEY_HIGHWAY);
                if (!Helper.isEmpty(highway) && speedLimitHandler.hasSpeedValue(highway)) {
                    double defaultSpeed = speedLimitHandler.getSpeed(highway);
                    if (defaultSpeed < maxSpeed)
                        maxSpeed = defaultSpeed;
                }
            }
        }

        return maxSpeed;
    }

    @Override
    protected String getHighway(ReaderWay way) {
        return way.getTag(KEY_HIGHWAY);
    }

    @Override
    public EncodingManager.Access getAccess(ReaderWay way) {
        String highwayValue = way.getTag(KEY_HIGHWAY);
        String[] restrictionValues = way.getFirstPriorityTagValues(restrictions);

        if (highwayValue == null) {
            if (way.hasTag("route", ferries)) {
                for (String restrictionValue : restrictionValues) {
                    if (restrictedValues.contains(restrictionValue))
                        return EncodingManager.Access.CAN_SKIP;
                    if (intendedValues.contains(restrictionValue))
                        return EncodingManager.Access.FERRY;
                }
                // implied default is allowed only if foot and bicycle is not specified:
                if (restrictionValues.length == 0 && !way.hasTag("foot") && !way.hasTag("bicycle")) {
                    return EncodingManager.Access.FERRY;
                }
            }
            return EncodingManager.Access.CAN_SKIP;
        }

        // Czy autobus/psv ma tu jawne uprawnienie (bus=yes/designated, psv=yes/designated, ...)
        boolean busAllowedHere = way.hasTag(busAccess, intendedValues);
        boolean dedicatedBusway = VAL_BUSWAY.equals(highwayValue) || VAL_BUS_GUIDEWAY.equals(highwayValue);

        // Drogi serwisowe prowadzące na parkingi/podjazdy — pomijamy, chyba że jawnie dla autobusu.
        String serviceTag = way.getTag("service");
        if (serviceTag != null && ("parking_aisle".equals(serviceTag) || "driveway".equals(serviceTag)) && !busAllowedHere) {
            return EncodingManager.Access.CAN_SKIP;
        }

        // Autobus miejski nie jeździ po drogach gruntowych (track), chyba że jawnie dopuszczony.
        if (VAL_TRACK.equals(highwayValue) && !busAllowedHere)
            return EncodingManager.Access.CAN_SKIP;

        if (!dedicatedBusway && !speedLimitHandler.hasSpeedValue(highwayValue))
            return EncodingManager.Access.CAN_SKIP;

        if (way.hasTag(KEY_IMPASSABLE, "yes") || way.hasTag("status", KEY_IMPASSABLE) || way.hasTag("smoothness", KEY_IMPASSABLE))
            return EncodingManager.Access.CAN_SKIP;

        // Ograniczenia fizyczne pojazdu z surowego OSM — twarde, bez wyjątku dla bus=yes
        // (autobus fizycznie nie zmieści się w węższej/niższej krawędzi).
        if (exceedsPhysicalLimit(way))
            return EncodingManager.Access.CAN_SKIP;

        boolean carsAllowed = way.hasTag(restrictions, intendedValues);
        for (String restrictionValue : restrictionValues) {
            if (!restrictionValue.isEmpty()) {
                if (restrictedValues.contains(restrictionValue)
                        && !getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way)
                        && !busAllowedHere)
                    return EncodingManager.Access.CAN_SKIP;
                if (intendedValues.contains(restrictionValue))
                    return EncodingManager.Access.WAY;
            }
        }

        // Droga zamknięta dla ruchu ogólnego, ale autobus/psv ma uprawnienie → dostępna.
        if (way.hasTag(restrictions, restrictedValues) && !carsAllowed && !busAllowedHere) {
            return EncodingManager.Access.CAN_SKIP;
        }

        // Dedykowana infrastruktura autobusowa bez ogólnych tagów dostępu — dopuszczamy wprost.
        if (dedicatedBusway)
            return EncodingManager.Access.WAY;

        if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford")) && !carsAllowed)
            return EncodingManager.Access.CAN_SKIP;

        if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way))
            return EncodingManager.Access.CAN_SKIP;
        else
            return EncodingManager.Access.WAY;
    }

    /**
     * Sprawdza, czy krawędź przekracza fizyczne ograniczenia autobusu na podstawie surowych
     * tagów OSM maxwidth/maxheight/maxlength/maxweight.
     */
    private boolean exceedsPhysicalLimit(ReaderWay way) {
        return dimensionBelow(way, "maxwidth", BUS_MAX_WIDTH)
                || dimensionBelow(way, "maxheight", BUS_MAX_HEIGHT)
                || dimensionBelow(way, "maxlength", BUS_MAX_LENGTH);
    }

    /**
     * Czy limit gabarytu jest mniejszy niż dany wymiar autobusu. Wariant specyficzny dla transportu
     * publicznego (np. maxheight:bus, maxheight:psv) ma pierwszeństwo nad limitem ogólnym — pozwala
     * to uszanować jawny wyjątek "nie dotyczy autobusów" tam, gdzie OSM go taguje.
     */
    private boolean dimensionBelow(ReaderWay way, String key, double busDimension) {
        String value = way.getTag(key + ":bus");
        if (value == null)
            value = way.getTag(key + ":psv");
        if (value == null)
            value = way.getTag(key);
        if (value == null)
            return false;
        double m = OSMValueExtractor.stringToMeter(value);
        return m > 0 && m < Double.MAX_VALUE && m < busDimension;
    }

    /**
     * Override oneway detection for bus/PSV exemptions.
     * Buses can go against one-way when oneway:psv=no, oneway:bus=no,
     * busway=opposite_lane, or when bus:backward/psv:backward is explicitly allowed.
     */
    @Override
    protected boolean isOneway(ReaderWay way) {
        // PSV/bus zwolnione z jednokierunkowości
        if (way.hasTag("oneway:psv", "no") || way.hasTag("oneway:bus", "no")) {
            return false;
        }

        // Kontrapas autobusowy
        if (way.hasTag("busway", "opposite_lane")
                || way.hasTag("busway:left", "opposite_lane")
                || way.hasTag("busway:right", "opposite_lane")) {
            return false;
        }

        if (super.isOneway(way)) {
            boolean isReverseOneway = way.hasTag("oneway", "-1");

            if (isReverseOneway) {
                // Reverse oneway: jeśli bus/psv ma dostęp forward → dwukierunkowa
                if (way.hasTag(new ArrayList<>(forwardKeys), intendedValues)) {
                    return false;
                }
            } else {
                // Forward oneway: jeśli bus/psv ma dostęp backward → dwukierunkowa
                if (way.hasTag(new ArrayList<>(backwardKeys), intendedValues)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Autobus przejeżdża przez bramki/słupki oznaczone dla transportu publicznego
     * (np. barrier=bollard + bus=yes), niezależnie od ogólnych reguł barier.
     */
    @Override
    public long handleNodeTags(ReaderNode node) {
        // Bus trap przepuszcza autobus z definicji (niezależnie od block_barriers);
        // bramki/słupki jawnie oznaczone dla transportu publicznego również.
        if (node.hasTag("barrier", "bus_trap")
                || (node.hasTag("barrier") && node.hasTag(busAccess, intendedValues))) {
            return 0;
        }

        // Szlabany/rogatki (barrier=lift_gate) — m.in. automatyczne rogatki przejazdów kolejowych —
        // bywają zmapowane jako osobne węzły na jezdni (tag railway=level_crossing siedzi zwykle na
        // INNYM węźle, więc nie da się go tu sprawdzić). Taki szlaban otwiera się dla ruchu drogowego,
        // a blokowanie go odcina przelot przez przejazd. Przykład: PKP Legionowo Piaski (linia 731,
        // route 481689) — węzły 13062001221/13062001220 przecinały Piaskową, przez co autobus
        // objeżdżał Szwajcarską/Kolejową. Dla profilu bus traktujemy lift_gate jako przejezdny,
        // chyba że jest jawnie zamknięty (locked=yes) lub ma jawną restrykcję dostępu (np. access=private).
        if (node.hasTag("barrier", "lift_gate") && !node.hasTag("locked", "yes")) {
            boolean restricted = false;
            for (String res : restrictions) {
                if (node.hasTag(res, restrictedValues)) {
                    restricted = true;
                    break;
                }
            }
            if (!restricted)
                return 0;
        }

        return super.handleNodeTags(node);
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, EncodingManager.Access access, long relationFlags) {
        super.handleWayTags(edgeFlags, way, access, relationFlags);

        priorityWayEncoder.setDecimal(false, edgeFlags, PriorityCode.getFactor(handlePriority(way)));
        if (isBusPreferredWay(way))
            busPreferredEncoder.setBool(false, edgeFlags, true);
        if (isOnBusRoute(way))
            busOnRouteEncoder.setBool(false, edgeFlags, true);
        return edgeFlags;
    }

    /**
     * Czy krawędź to preferowana infrastruktura autobusowa — sygnał dla custom_model.
     */
    private boolean isBusPreferredWay(ReaderWay way) {
        String highway = way.getTag(KEY_HIGHWAY);
        if (VAL_BUSWAY.equals(highway) || VAL_BUS_GUIDEWAY.equals(highway))
            return true;
        if (way.hasTag("bus_guideway", "yes"))
            return true;
        if (way.hasTag("bus", VAL_DESIGNATED) || way.hasTag("psv", VAL_DESIGNATED))
            return true;
        // Jawne dopuszczenie autobusu/psv (bus=yes / psv=yes) — m.in. zatoki/pętle przystankowe
        // tagowane highway=service + psv=yes (np. dojazdy do Dw. Centralnego). Oznaczenie ich jako
        // "preferowane" pozwala custom_model karać mocno DROGI SERWISOWE NIE-autobusowe (parkingi,
        // dojazdy) bez uderzania w legalne zatoki — patrz reguła SERVICE && bus$preferred==false.
        if (way.hasTag("bus", "yes") || way.hasTag("psv", "yes"))
            return true;
        if (way.hasTag("busway", "lane") || way.hasTag("busway", "opposite_lane")
                || way.hasTag("busway:left") || way.hasTag("busway:right"))
            return true;
        return way.hasTag("lanes:psv") || way.hasTag("lanes:bus");
    }

    /**
     * Czy krawędź jest częścią ≥1 relacji OSM route=bus — sygnał systemowy zastępujący
     * heurystykę "maxspeed otagowany ⇒ to nie skrót" w custom_model. Tag bus:on_route=yes
     * NIE istnieje natywnie w OSM — jest wstrzykiwany przez fix_private_roads.py na podstawie
     * tabeli PostGIS bus_route_ways (cron/osm2pgsql/import/bus_routes.lua), niezależnego
     * pipeline'u od buildu grafu ORS.
     */
    private boolean isOnBusRoute(ReaderWay way) {
        return way.hasTag("bus:on_route", "yes");
    }

    protected int handlePriority(ReaderWay way) {
        TreeMap<Double, Integer> weightToPrioMap = new TreeMap<>();
        collect(way, weightToPrioMap);
        return weightToPrioMap.lastEntry().getValue();
    }

    /**
     * Wszystkie krawędzie dostają BEST jako neutralny baseline — faktyczne preferencje nakłada
     * custom_model po stronie klienta (per-request, bez rebuildu grafu).
     */
    protected void collect(ReaderWay way, TreeMap<Double, Integer> weightToPrioMap) {
        weightToPrioMap.put(100d, PriorityCode.BEST.getValue());
    }

    @Override
    public boolean supports(Class<?> feature) {
        if (super.supports(feature))
            return true;
        return PriorityWeighting.class.isAssignableFrom(feature);
    }

    public double getMeanSpeed() {
        return MEAN_SPEED;
    }

    @Override
    public String toString() {
        return FlagEncoderNames.BUS;
    }

    @Override
    public TransportationMode getTransportationMode() {
        return TransportationMode.PSV;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final BusFlagEncoder other = (BusFlagEncoder) obj;
        return toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return ("BusFlagEncoder" + this).hashCode();
    }
}

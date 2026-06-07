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

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.ConditionalOSMSpeedInspector;
import com.graphhopper.reader.osm.conditional.ConditionalParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValue;
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

public class HeavyVehicleFlagEncoder extends VehicleFlagEncoder {
    public static final String VAL_DESIGNATED = "designated";
    public static final String VAL_AGRICULTURAL = "agricultural";
    public static final String VAL_FORESTRY = "forestry";
    public static final String VAL_GOODS = "goods";
    public static final String KEY_HIGHWAY = "highway";
    public static final String VAL_TRACK = "track";
    public static final String KEY_IMPASSABLE = "impassable";
    protected final HashSet<String> forwardKeys = new HashSet<>(5);
    protected final HashSet<String> backwardKeys = new HashSet<>(5);
    protected final List<String> hgvAccess = new ArrayList<>(5);
    // Lista tagów dostępu specyficznych dla autobusów i transportu publicznego
    protected final List<String> busAccess = new ArrayList<>(5);

    private static final int MEAN_SPEED = 70;

    // Encoder for storing whether the edge is on a preferred way
    private DecimalEncodedValue priorityWayEncoder;

    /**
     * Should be only instantied via EncodingManager
     */
    public HeavyVehicleFlagEncoder() {
        this(5, 5, 0);
    }

    public HeavyVehicleFlagEncoder(PMap properties) {
        this(properties.getInt("speed_bits", 5),
                properties.getDouble("speed_factor", 5),
                properties.getBool("turn_costs", false) ? 3 : 0);

        setProperties(properties);

        maxTrackGradeLevel = properties.getInt("maximum_grade_level", 1);
    }

    public HeavyVehicleFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);

        maxPossibleSpeed = 90;

        intendedValues.add(VAL_DESIGNATED);
        intendedValues.add(VAL_AGRICULTURAL);
        intendedValues.add(VAL_FORESTRY);
        intendedValues.add("delivery");
        intendedValues.add("bus");
        intendedValues.add("hgv");
        intendedValues.add(VAL_GOODS);
        intendedValues.add("psv");
        intendedValues.add("public_transport");

        restrictedValues.add(VAL_AGRICULTURAL);
        restrictedValues.add(VAL_FORESTRY);
        restrictedValues.add("emergency");

        blockByDefaultBarriers.add("sump_buster");

        // Bus traps are designed to let buses through
        passByDefaultBarriers.add("bus_trap");

        hgvAccess.addAll(Arrays.asList("hgv", VAL_GOODS, "bus", VAL_AGRICULTURAL, VAL_FORESTRY, "delivery"));
        busAccess.addAll(Arrays.asList("bus", "psv", "public_transport"));

        // Override default speeds with lower values
        trackTypeSpeedMap.put("grade1", 40); // paved
        trackTypeSpeedMap.put("grade2", 30); // now unpaved - gravel mixed with ...
        trackTypeSpeedMap.put("grade3", 20); // ... hard and soft materials
        trackTypeSpeedMap.put("grade4", 15); // ... some hard or compressed materials
        trackTypeSpeedMap.put("grade5", 10); // ... no hard materials. soil/sand/grass
        // autobahn
        defaultSpeedMap.put("motorway", 80);
        defaultSpeedMap.put("motorway_link", 50);
        defaultSpeedMap.put("motorroad", 80);
        // bundesstraße
        defaultSpeedMap.put("trunk", 80);
        defaultSpeedMap.put("trunk_link", 50);
        // linking bigger town
        defaultSpeedMap.put("primary", 60);

        initSpeedLimitHandler(this.toString());

        forwardKeys.add("goods:forward");
        forwardKeys.add("hgv:forward");
        forwardKeys.add("bus:forward");
        forwardKeys.add("agricultural:forward");
        forwardKeys.add("forestry:forward");
        forwardKeys.add("delivery:forward");

        backwardKeys.add("goods:backward");
        backwardKeys.add("hgv:backward");
        backwardKeys.add("bus:backward");
        backwardKeys.add("agricultural:backward");
        backwardKeys.add("forestry:backward");
        backwardKeys.add("delivery:backward");

        backwardKeys.add("psv:backward");
        forwardKeys.add("psv:forward");
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
    }

    @Override
    public double getMaxSpeed(ReaderWay way) {
        // Sprawdzamy czy jest specjalny limit prędkości dla autobusów
        double maxSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus"));

        double fwdSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus:forward"));
        if (isValidSpeed(fwdSpeed) && (!isValidSpeed(maxSpeed) || fwdSpeed < maxSpeed)) {
            maxSpeed = fwdSpeed;
        }

        double backSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus:backward"));
        if (isValidSpeed(backSpeed) && (!isValidSpeed(maxSpeed) || backSpeed < maxSpeed)) {
            maxSpeed = backSpeed;
        }

        // Fallback na maxspeed:hgv
        if (!isValidSpeed(maxSpeed)) {
            maxSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:hgv"));

            fwdSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:hgv:forward"));
            if (isValidSpeed(fwdSpeed) && (!isValidSpeed(maxSpeed) || fwdSpeed < maxSpeed)) {
                maxSpeed = fwdSpeed;
            }

            backSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:hgv:backward"));
            if (isValidSpeed(backSpeed) && (!isValidSpeed(maxSpeed) || backSpeed < maxSpeed)) {
                maxSpeed = backSpeed;
            }
        }

        // Fallback na standardowy maxspeed
        if (!isValidSpeed(maxSpeed)) {
            maxSpeed = super.getMaxSpeed(way);
            if (isValidSpeed(maxSpeed)) {
                String highway = way.getTag(KEY_HIGHWAY);
                if (!Helper.isEmpty(highway)) {
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

        // Obsługa tagu routing:ztm - specjalny tag do sterowania trasami ZTM
        String ztmRouteTag = way.getTag("routing:ztm");
        if (ztmRouteTag != null) {
            if ("no".equals(ztmRouteTag)) {
                return EncodingManager.Access.CAN_SKIP;
            }
        }

        // Pomijamy drogi serwisowe prowadzące na parkingi
        String serviceTag = way.getTag("service");
        if (serviceTag != null) {
            if ("parking_aisle".equals(serviceTag)) {
                return EncodingManager.Access.CAN_SKIP;
            }
        }

        // Pomijamy podjazdy
        if (serviceTag != null) {
            if ("driveway".equals(serviceTag)) {
                return EncodingManager.Access.CAN_SKIP;
            }
        }

        if (VAL_TRACK.equals(highwayValue)) {
            String tt = way.getTag("tracktype");
            int grade = getTrackGradeLevel(tt);
            if (grade > maxTrackGradeLevel)
                return EncodingManager.Access.CAN_SKIP;
        }

        if (!speedLimitHandler.hasSpeedValue(highwayValue))
            return EncodingManager.Access.CAN_SKIP;

        if (way.hasTag(KEY_IMPASSABLE, "yes") || way.hasTag("status", KEY_IMPASSABLE) || way.hasTag("smoothness", KEY_IMPASSABLE))
            return EncodingManager.Access.CAN_SKIP;

        // multiple restrictions needs special handling compared to foot and bike, see also motorcycle
        boolean carsAllowed = way.hasTag(restrictions, intendedValues);
        for (String restrictionValue : restrictionValues) {
            if (!restrictionValue.isEmpty()) {
                if (restrictedValues.contains(restrictionValue) && !getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                    return EncodingManager.Access.CAN_SKIP;
                if (intendedValues.contains(restrictionValue))
                    return EncodingManager.Access.WAY;
            }
        }

        // Sprawdzenie dostępu - jeśli droga ma ograniczenia, ale autobus/hgv ma specjalne uprawnienia
        if (way.hasTag(restrictions, restrictedValues) && !carsAllowed && !way.hasTag(hgvAccess, intendedValues) && !way.hasTag(busAccess, intendedValues)) {
            return EncodingManager.Access.CAN_SKIP;
        }

        // do not drive street cars into fords
        if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford")) && !carsAllowed)
            return EncodingManager.Access.CAN_SKIP;

        // maxwidth filter przeniesiony do options.profile_params.restrictions.width
        // w web/app/_service/directions/orsBusCustomModel.ts (BUS_RESTRICTIONS)

        if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way))
            return EncodingManager.Access.CAN_SKIP;
        else
            return EncodingManager.Access.WAY;
    }

    /**
     * Mnożniki prędkości highway-based przeniesione do custom_model po stronie klienta —
     * patrz BUS_CUSTOM_MODEL.speed w orsBusCustomModel.ts.
     *
     * Bonusy dla bus_guideway (1.8x) i bus/psv=designated (1.6x) zostają tu do czasu
     * fazy 3 refaktoru (rejestracja BooleanEncodedValue bus_priority), bo custom_model
     * nie umie warunkować na tych tagach bez własnego EncodedValue.
     */
    @Override
    protected double getSpeed(ReaderWay way) {
        String highway = way.getTag(KEY_HIGHWAY);
        double speed = super.getSpeed(way);

        if ("bus_guideway".equals(highway) || way.hasTag("bus_guideway", "yes")) {
            return speed * 1.8;
        }

        if (way.hasTag("bus", VAL_DESIGNATED) || way.hasTag("psv", VAL_DESIGNATED)) {
            return speed * 1.6;
        }

        return speed;
    }

    /**
     * Override oneway detection for bus/PSV exemptions.
     * Buses can go against one-way when oneway:psv=no, oneway:bus=no,
     * or when bus:backward/psv:backward is explicitly allowed on a forward oneway.
     */
    @Override
    protected boolean isOneway(ReaderWay way) {
        // PSV/bus exempt from one-way restriction
        if (way.hasTag("oneway:psv", "no") || way.hasTag("oneway:bus", "no")) {
            return false;
        }

        if (super.isOneway(way)) {
            boolean isReverseOneway = way.hasTag("oneway", "-1");

            if (isReverseOneway) {
                // Reverse oneway: if bus/psv has forward access → bidirectional
                if (way.hasTag(new ArrayList<>(forwardKeys), intendedValues)) {
                    return false;
                }
            } else {
                // Forward oneway: if bus/psv has backward access → bidirectional
                if (way.hasTag(new ArrayList<>(backwardKeys), intendedValues)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, EncodingManager.Access access, long relationFlags) {
        super.handleWayTags(edgeFlags, way, access, relationFlags);

        priorityWayEncoder.setDecimal(false, edgeFlags, PriorityCode.getFactor(handlePriority(way)));
        return edgeFlags;
    }

    protected int handlePriority(ReaderWay way) {
        TreeMap<Double, Integer> weightToPrioMap = new TreeMap<>();

        collect(way, weightToPrioMap);

        // pick priority with biggest order value
        return weightToPrioMap.lastEntry().getValue();
    }

    /**
     * Highway-based priorytety przeniesione do BUS_CUSTOM_MODEL.priority w orsBusCustomModel.ts.
     * Tutaj wszystkie krawędzie dostają BEST jako neutralny baseline — custom_model
     * nakłada faktyczne preferencje per-request. Pozwala to tunować priorytety bez
     * rebuildu grafu / kontenera ORS.
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
        return FlagEncoderNames.HEAVYVEHICLE;
    }

    @Override
    public TransportationMode getTransportationMode() {
        return TransportationMode.HGV;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final HeavyVehicleFlagEncoder other = (HeavyVehicleFlagEncoder) obj;
        return toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return ("HeavyVehicleFlagEncoder" + this).hashCode();
    }
}
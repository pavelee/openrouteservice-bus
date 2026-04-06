/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.ConditionalOSMSpeedInspector;
import com.graphhopper.reader.osm.conditional.ConditionalParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.util.PMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Defines bit layout for cars. (speed, access, ferries, ...)
 * <p>
 *
 * @author Peter Karich
 * @author Nop
 */
public class CarFlagEncoder extends VehicleFlagEncoder {

    private static final String KEY_IMPASSABLE = "impassable";

    // Mean speed for isochrone reach_factor
    private static final int MEAN_SPEED = 100;

    // Kolekcje do obsługi kierunkowych tagów dla autobusów
    // Autobusy często mają specjalne uprawnienia do jazdy pod prąd w niektórych ulicach
    // np. oneway=yes ale bus:backward=yes
    protected final HashSet<String> forwardKeys = new HashSet<>(5);
    protected final HashSet<String> backwardKeys = new HashSet<>(5);
    
    // Lista tagów dostępu specyficznych dla autobusów i transportu publicznego
    // PSV (Public Service Vehicle) i public_transport to tagi używane
    // do oznaczania infrastruktury dla transportu publicznego
    protected final List<String> busAccess = new ArrayList<>(5);

    public CarFlagEncoder(PMap properties) {
        this(properties.getInt("speed_bits", 5),
                properties.getDouble("speed_factor", 5),
                properties.getBool("turn_costs", false) ? 1 : 0);

        setProperties(properties);
    }

    public CarFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);

        // Dodajemy obsługę autobusów - autobusy mają często specjalne oznaczenia
        // bus=yes lub bus=designated które pozwalają im korzystać z dróg 
        // niedostępnych dla zwykłych samochodów
        intendedValues.add("bus");
        intendedValues.add("psv"); // Public Service Vehicle - transport publiczny
        intendedValues.add("public_transport"); // ogólny tag dla transportu publicznego

        restrictedValues.add("agricultural");
        restrictedValues.add("forestry");
        // restrictedValues.add("delivery");
        restrictedValues.add("emergency");

        // blockByDefaultBarriers.add("bus_trap");
        blockByDefaultBarriers.add("sump_buster");

        // Bus traps are designed to let buses through
        passByDefaultBarriers.add("bus_trap");

        // Inicjalizacja kolekcji dla autobusów
        // busAccess - lista tagów dostępu specyficznych dla autobusów
        // Pozwala autobusowi korzystać z dróg ograniczonych dla samochodów osobowych
        busAccess.addAll(Arrays.asList("bus", "psv", "public_transport"));

        // Obsługa kierunkowych tagów dla autobusów
        // Autobusy mogą mieć różne uprawnienia w różnych kierunkach
        // np. bus:forward=yes pozwala jazde w kierunku zgodnym z numeracją
        forwardKeys.add("bus:forward");
        forwardKeys.add("psv:forward");
        
        backwardKeys.add("bus:backward");
        backwardKeys.add("psv:backward");

        initSpeedLimitHandler(this.toString());
    }

    @Override
    protected void init(DateRangeParser dateRangeParser) {
        super.init(dateRangeParser);
        ConditionalOSMSpeedInspector conditionalOSMSpeedInspector = new ConditionalOSMSpeedInspector(List.of("maxspeed"));
        conditionalOSMSpeedInspector.addValueParser(ConditionalParser.createDateTimeParser());
        setConditionalSpeedInspector(conditionalOSMSpeedInspector);
    }

    @Override
    public EncodingManager.Access getAccess(ReaderWay way) {
        // TODO: Ferries have conditionals, like opening hours or are closed during some time in the year
        String highwayValue = way.getTag("highway");
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

        // bus ?
        String ztmRouteTag = way.getTag("routing:ztm");
        if (ztmRouteTag != null) {
            if ("no".equals(ztmRouteTag)) {
                return EncodingManager.Access.CAN_SKIP;
            }
            // if ("yes".equals(ztmRouteTag)) {
            //     return EncodingManager.Access.WAY;
            // }
        }

        // we ommit ways to parking_aisle
        String serviceTag = way.getTag("service");
        if (serviceTag != null) {
            if ("parking_aisle".equals(serviceTag)) {
                return EncodingManager.Access.CAN_SKIP;
            }
        }

        // we ommit driveways
        if (serviceTag != null) {
            if ("driveway".equals(serviceTag)) {
                return EncodingManager.Access.CAN_SKIP;
            }
        }

        if ("track".equals(highwayValue)) {
            String tt = way.getTag("tracktype");
            if (tt != null) {
                int grade = getTrackGradeLevel(tt);
                if (grade > maxTrackGradeLevel)
                    return EncodingManager.Access.CAN_SKIP;
            }
        }

        if (!speedLimitHandler.hasSpeedValue(highwayValue))
            return EncodingManager.Access.CAN_SKIP;

        if (way.hasTag(KEY_IMPASSABLE, "yes") || way.hasTag("status", KEY_IMPASSABLE) || way.hasTag("smoothness", KEY_IMPASSABLE))
            return EncodingManager.Access.CAN_SKIP;

        // multiple restrictions needs special handling compared to foot and bike, see also motorcycle
        // Sprawdzamy czy autobus ma specjalne uprawnienia dostępu
        boolean carsAllowed = way.hasTag(restrictions, intendedValues);
        for (String restrictionValue : restrictionValues) {
            if (!restrictionValue.isEmpty()) {
                if (restrictedValues.contains(restrictionValue))
                    return isRestrictedWayConditionallyPermitted(way);
                if (intendedValues.contains(restrictionValue))
                    return EncodingManager.Access.WAY;
            }
        }

        // Sprawdzenie dostępu dla autobusów - kluczowa logika z HeavyVehicleFlagEncoder
        // Jeśli droga ma ograniczenia dla samochodów ale NIE ma specjalnych uprawnień dla autobusów
        // to autobus nie może tam jechać. Autobusy mają specjalne tagi jak bus=yes, psv=yes
        if (way.hasTag(restrictions, restrictedValues) && !carsAllowed && !way.hasTag(busAccess, intendedValues)) {
            return EncodingManager.Access.CAN_SKIP;
        }

        // do not drive street cars into fords
        if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford")))
            return EncodingManager.Access.CAN_SKIP;


        String maxwidth = way.getTag("maxwidth"); // Runge added on 23.02.2016
        if (maxwidth != null) {
            try {
                double mwv = Double.parseDouble(maxwidth);
                if (mwv < 2.5)
                    return EncodingManager.Access.CAN_SKIP;
            } catch (Exception ex) {
                // ignore
            }
        }

        return isPermittedWayConditionallyRestricted(way);
    }

    /**
     * Modyfikacja prędkości dla autobusów miejskich - preferuj główne drogi
     * @param way droga OSM
     * @return zmodyfikowana prędkość
     */
    @Override
    protected double getSpeed(ReaderWay way) {
        String highway = way.getTag("highway");
        double speed = super.getSpeed(way);
        
        // Najwyższe bonusy dla infrastruktury autobusowej
        if ("bus_guideway".equals(highway) || way.hasTag("bus_guideway", "yes")) {
            return speed * 1.8; // 80% bonus dla pasów autobusowych
        }
        
        if (way.hasTag("bus", "designated") || way.hasTag("psv", "designated")) {
            return speed * 1.6; // 60% bonus dla dróg dedykowanych dla autobusów
        }
        
        // Autobusy miejskie - niewielkie bonusy dla dróg szybkiego ruchu,
        // ale NIE karzemy dróg osiedlowych bo autobusy regularnie nimi jeżdżą
        if ("motorway".equals(highway) || "motorway_link".equals(highway)) {
            return speed * 1.2;
        } else if ("trunk".equals(highway) || "trunk_link".equals(highway)) {
            return speed * 1.15;
        } else if ("primary".equals(highway) || "primary_link".equals(highway)) {
            return speed * 1.1;
        } else if ("secondary".equals(highway) || "secondary_link".equals(highway)) {
            return speed * 1.05;
        } else if ("tertiary".equals(highway) || "tertiary_link".equals(highway)) {
            return speed * 1.0;
        }
        
        // Drogi osiedlowe - bez kary, autobusy regularnie nimi jeżdżą
        else if ("residential".equals(highway)) {
            return speed * 1.0;
        } else if ("unclassified".equals(highway)) {
            return speed * 1.0;
        } else if ("service".equals(highway)) {
            return speed * 0.7;
        } else if ("living_street".equals(highway)) {
            return speed * 0.5;
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

    /**
     * Obsługa specjalnych ograniczeń prędkości dla autobusów
     * Autobusy mogą mieć inne limity prędkości niż samochody osobowe
     * @param way droga OSM
     * @return maksymalna prędkość dla autobusu na tej drodze
     */
    @Override
    public double getMaxSpeed(ReaderWay way) {
        // Sprawdzamy czy jest specjalny limit prędkości dla autobusów
        double maxSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus"));
        
        // Sprawdzamy kierunkowe limity prędkości dla autobusów
        double fwdSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus:forward"));
        if (isValidSpeed(fwdSpeed) && (!isValidSpeed(maxSpeed) || fwdSpeed < maxSpeed)) {
            maxSpeed = fwdSpeed;
        }

        double backSpeed = OSMValueExtractor.stringToKmh(way.getTag("maxspeed:bus:backward"));
        if (isValidSpeed(backSpeed) && (!isValidSpeed(maxSpeed) || backSpeed < maxSpeed)) {
            maxSpeed = backSpeed;
        }
        
        // Jeśli nie ma specjalnych limitów dla autobusów, używamy standardowej logiki
        if (!isValidSpeed(maxSpeed)) {
            maxSpeed = super.getMaxSpeed(way);
        }
        
        return maxSpeed;
    }

    public double getMeanSpeed() {
        return MEAN_SPEED;
    }

    @Override
    public String toString() {
        return FlagEncoderNames.CAR_ORS;
    }

    @Override
    public TransportationMode getTransportationMode() {
        return TransportationMode.CAR;
    }
}

/*
 * Copyright 2014-2016 GraphHopper GmbH.
 *
 * NOTICE:  All information contained herein is, and remains the property of
 * GraphHopper GmbH. The intellectual and technical concepts contained herein
 * are proprietary to GraphHopper GmbH, and are protected by trade secret
 * or copyright law. Dissemination of this information or reproduction of
 * this material is strictly forbidden unless prior written permission
 * is obtained from GraphHopper GmbH.
 */
package com.graphhopper.routing.ee.vehicles;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.ConditionalOSMTagInspector;
import com.graphhopper.reader.osm.conditional.ConditionalParser;
import com.graphhopper.reader.osm.conditional.ConditionalValueParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Truck encoder with various properties. See truck limits bugs in OSM e.g.
 * maxheights etc: http://maxheight.bplaced.net/overpass/map.html
 * <p>
 *
 * @author Peter Karich
 */
public class TruckFlagEncoder extends CarFlagEncoder {
    public static final double SMALL_TRUCK_WEIGHT = 2.080 + 1.400;

    public static TruckFlagEncoder createCar(PMap properties, String name) {
        setDefaultProperties(properties, 7, 2, 1);
        return new TruckFlagEncoder(properties, name).initProperties();
    }

    /**
     * Describes a van like the Mercedes vito or Ford transit. Here we use the
     * values for the Vito compact. Total weight is under 3.5t
     */
    public static TruckFlagEncoder createVan(String name) {
        return createVan(new PMap(), name);
    }

    public static TruckFlagEncoder createVan(PMap properties, String name) {
        setDefaultProperties(properties, 7, 2, 1);
        return new TruckFlagEncoder(properties, name).
                setHeight(2.5).setWidth(1.91, 0.4).setLength(4.75).
                setWeight(1.660 + 1.110).
                initProperties();
    }

    /**
     * Describes a so called vehicle like the Mercedes Sprinter or Iveco Daily
     * used by DHL. Total weight is under 3.5t
     */
    public static TruckFlagEncoder createSmallTruck(String name) {
        return createSmallTruck(new PMap(), name);
    }

    public static TruckFlagEncoder createSmallTruck(PMap properties, String name) {
        setDefaultProperties(properties, 7, 2, 1);
        return new TruckFlagEncoder(properties, name).
                setHeight(2.7).setWidth(2, 0.4).setLength(5.5).
                setWeight(SMALL_TRUCK_WEIGHT).
                initProperties();
    }

    /**
     * Describes a big HGV truck with 3 axes. E.g. the 6 wheeler here:
     * http://www.grabtrucks.com/willitfit/ where we only increased the length
     */
    public static TruckFlagEncoder createTruck(String name) {
        return createTruck(new PMap(), name);
    }

    public static TruckFlagEncoder createTruck(PMap properties, String name) {
        setDefaultProperties(properties, 6, 2, 1);
        return new TruckFlagEncoder(properties, name).
                setHeight(3.7).setWidth(2.6, 0.34).setLength(12).
                setWeight(13.000 + 13.000).setAxes(3).setIsHGV(true).
                initProperties();
    }

    private static void setDefaultProperties(PMap properties, int speedBits, double speedFactor, int maxTurnCosts) {
        if (!properties.has("speed_bits"))
            properties.putObject("speed_bits", speedBits);
        if (!properties.has("speed_factor"))
            properties.putObject("speed_factor", speedFactor);
        if (!properties.has("turn_costs") && !properties.has("max_turn_costs"))
            properties.putObject("max_turn_costs", maxTurnCosts);
    }

    private static final Logger logger = LoggerFactory.getLogger(TruckFlagEncoder.class);
    private final String name;
    // default settings "isCarLike == true"
    private boolean isHGV = false;
    private boolean carriesGoods = true;
    private boolean carriesHazard = false;
    private boolean isAgricultural = false;
    private double length = 4.5;
    private double width = 2;
    private double mirrorWidth = 0;
    private double height = 2;
    private double weight = 2.500;
    private double excludeMaxSpeed;
    private int axes = 2;

    public TruckFlagEncoder(PMap properties, String profileName) {
        super(properties);

        if (!properties.getBool("block_private", true)) {
            restrictedValues.remove("private");
            intendedValues.add("private");
            intendedValues.add("destination");
        }
        if (!properties.getBool("block_delivery", true)) {
            restrictedValues.remove("delivery");
            intendedValues.add("delivery");
        }
        this.name = profileName;
        this.maxPossibleSpeed = 0;
    }

    public TruckFlagEncoder setHeight(double height) {
        this.height = height;
        return this;
    }

    public TruckFlagEncoder setLength(double length) {
        this.length = length;
        return this;
    }

    public TruckFlagEncoder setWidth(double width, double mirrorWidth) {
        this.width = width;
        this.mirrorWidth = mirrorWidth;
        return this;
    }

    public TruckFlagEncoder setAxes(int axes) {
        this.axes = axes;
        return this;
    }

    /**
     * Sets the weight of the vehicle including people, equipment and payload in tons
     */
    public TruckFlagEncoder setWeight(double weight) {
        this.weight = weight;
        return this;
    }

    /**
     * Sets if this vehicle should be a heavy goods vehicle
     */
    public TruckFlagEncoder setIsHGV(boolean isHGV) {
        this.isHGV = isHGV;
        return this;
    }

    public boolean isHGV() {
        return isHGV;
    }

    public TruckFlagEncoder setCarriesGoods(boolean carriesGoods) {
        this.carriesGoods = carriesGoods;
        return this;
    }

    public TruckFlagEncoder setCarriesHazard(boolean carriesHazard) {
        this.carriesHazard = carriesHazard;
        return this;
    }

    public TruckFlagEncoder setIsAgricultural(boolean isAgricultural) {
        this.isAgricultural = isAgricultural;
        return this;
    }

    public TruckFlagEncoder setMaxSpeed(int maxSpeed) {
        maxPossibleSpeed = maxSpeed;
        return this;
    }

    public TruckFlagEncoder setExcludeMaxSpeed(double maxSpeed) {
        excludeMaxSpeed = maxSpeed;
        return this;
    }

    public TruckFlagEncoder initProperties() {
        // TODO merge with init somehow?
        return initProperties(null);
    }

    /**
     * This method initialized the speed and the specified speedMap or car
     * speeds will be used for default speeds. Maps highway tags to speeds.
     */
    public TruckFlagEncoder initProperties(Map<String, Integer> speedMap) {
        if (maxPossibleSpeed > 0) {
            // already set
        } else if (isCarLike()) {
            maxPossibleSpeed = 140;
        } else if (isHGV) {
            maxPossibleSpeed = 95;
        } else {
            maxPossibleSpeed = 105;
        }

        final List<String> tmpRestrictions = new ArrayList<String>();
        if (isCarLike()) {
            // keep most values except the default track settings:
            trackTypeSpeedMap.clear();
            trackTypeSpeedMap.put("grade1", 15);
            trackTypeSpeedMap.put(null, 15);

        } else if (isAgricultural) {
            defaultSpeedMap.put("forestry", 15);
            defaultSpeedMap.remove("track");

            restrictions.remove("motorcar");
            tmpRestrictions.add("agricultural");
        } else {
            defaultSpeedMap.remove("track");

            restrictions.remove("motorcar");
        }

        // make width and weight dependent (currently three categories)
        if (isCarLike()) {
            // keep values
        } else if (weight <= 10.000 && width <= 2.7) {
            // small truck
            defaultSpeedMap.put("motorway", 90);
            defaultSpeedMap.put("motorway_link", 75);
            defaultSpeedMap.put("motorroad", 75);
            defaultSpeedMap.put("trunk", 70);
            defaultSpeedMap.put("trunk_link", 60);
            defaultSpeedMap.put("primary", 65);
            defaultSpeedMap.put("primary_link", 60);
            defaultSpeedMap.put("secondary", 55);
            defaultSpeedMap.put("secondary_link", 50);
            defaultSpeedMap.put("tertiary", 30);
            defaultSpeedMap.put("tertiary_link", 25);
            defaultSpeedMap.put("unclassified", 25);
            defaultSpeedMap.put("residential", 20);
            defaultSpeedMap.put("living_street", 5);
            defaultSpeedMap.put("service", 15);
            defaultSpeedMap.put("road", 15);
        } else {
            // wide enough trucks can pass this trap
            absoluteBarriers.remove("bus_trap");
            absoluteBarriers.remove("sump_buster");

            // truck / bus
            defaultSpeedMap.put("motorway", 80);
            defaultSpeedMap.put("motorway_link", 75);
            defaultSpeedMap.put("motorroad", 70);
            defaultSpeedMap.put("trunk", 70);
            defaultSpeedMap.put("trunk_link", 60);
            defaultSpeedMap.put("primary", 60);
            defaultSpeedMap.put("primary_link", 55);
            defaultSpeedMap.put("secondary", 55);
            defaultSpeedMap.put("secondary_link", 55);
            defaultSpeedMap.put("tertiary", 20);
            defaultSpeedMap.put("tertiary_link", 20);
            defaultSpeedMap.put("unclassified", 20);
            defaultSpeedMap.put("residential", 15);
            defaultSpeedMap.put("living_street", 5);
            defaultSpeedMap.put("service", 10);
            defaultSpeedMap.put("road", 10);
        }

        if (carriesHazard) {
            tmpRestrictions.add("hazmat");
        }

        if (isHGV) {
            tmpRestrictions.add("hgv");
            tmpRestrictions.add("goods");
        } else if (carriesGoods) {
            tmpRestrictions.add("goods");
        }

        tmpRestrictions.addAll(restrictions);
        restrictions.clear();
        restrictions.addAll(tmpRestrictions);

        if (speedMap != null) {
            for (Map.Entry<String, Integer> entry : speedMap.entrySet()) {
                if (entry.getValue() <= 0) {
                    defaultSpeedMap.remove(entry.getKey());
                } else {
                    defaultSpeedMap.put(entry.getKey(), entry.getValue());
                }
            }
        } else {
            // use car defaults
        }

        return this;
    }

    @Override
    protected void init(DateRangeParser dateRangeParser) {
        List<String> tmpRestrictions = new ArrayList<>(restrictions);
        Set<String> tmpIntendedValues = new HashSet<>(intendedValues);

        // see #197, special use case that needs a completely different conditional tagging schema: maxweight:conditional = none @ destination
        boolean specialAccess = intendedValues.contains("delivery") || intendedValues.contains("destination") || intendedValues.contains("private");
        if (specialAccess) {
            tmpRestrictions.add("maxweight");
            tmpRestrictions.add("maxheight");
            tmpRestrictions.add("maxwidth");
            tmpIntendedValues.add("none");
        }

        ConditionalOSMTagInspector condInspector = new ConditionalOSMTagInspector(Collections.emptyList(), tmpRestrictions,
                restrictedValues, tmpIntendedValues, false);
        condInspector.addValueParser(new DateRangeParser(DateRangeParser.createCalendar()));
        condInspector.addValueParser(ConditionalParser.createNumberParser("weight", weight));
        condInspector.addValueParser(ConditionalParser.createNumberParser("height", height));
        condInspector.addValueParser(ConditionalParser.createNumberParser("width", width));
        // consider also something like access:conditional = no @ hgv
        condInspector.addValueParser(conditional -> {
            // conditional can be e.g. hgv
            if (tmpRestrictions.contains(conditional))
                return ConditionalValueParser.ConditionState.TRUE;
            return ConditionalValueParser.ConditionState.INVALID;
        });
        if (specialAccess)
            condInspector.addValueParser(conditional -> {
                // conditional can be destination or private
                if (intendedValues.contains(conditional))
                    return ConditionalValueParser.ConditionState.TRUE;
                return ConditionalValueParser.ConditionState.INVALID;
            });

        super.setConditionalTagInspector(condInspector);
    }

    List<String> getRestrictions() {
        return restrictions;
    }

    boolean isCarLike() {
        return weight <= 3.000 && width <= 2 && length < 6;
    }

    double getDefaultSpeed(String key) {
        return defaultSpeedMap.get(key);
    }

    @Override
    public EncodingManager.Access getAccess(ReaderWay way) {
        if (maxPossibleSpeed < 1)
            throw new IllegalStateException("maxPossibleSpeed cannot be smaller 1 but was " + maxPossibleSpeed);

        String highwayValue = way.getTag("highway");
        String firstValue = way.getFirstPriorityTag(restrictions);
        if (highwayValue == null) {
            if (way.hasTag("route", ferries)) {
                if (restrictedValues.contains(firstValue))
                    return EncodingManager.Access.CAN_SKIP;
                if (intendedValues.contains(firstValue) ||
                        // implied default is allowed only if foot and bicycle is not specified:
                        firstValue.isEmpty() && !way.hasTag("foot") && !way.hasTag("bicycle") ||
                        // if hgv is allowed than smaller trucks and even cars are too, see #30
                        way.hasTag("hgv", "yes"))
                    return EncodingManager.Access.FERRY;
            }
            return EncodingManager.Access.CAN_SKIP;
        }

        if ("track".equals(highwayValue) && trackTypeSpeedMap.get(way.getTag("tracktype")) == null)
            return EncodingManager.Access.CAN_SKIP;

        if (!defaultSpeedMap.containsKey(highwayValue)) {
            return EncodingManager.Access.CAN_SKIP;
        }

        if (excludeMaxSpeed > 0 && getMaxSpeed(way) > excludeMaxSpeed) {
            return EncodingManager.Access.CAN_SKIP;
        }

        if (way.hasTag("impassable", "yes") || way.hasTag("status", "impassable")) {
            return EncodingManager.Access.CAN_SKIP;
        }

        if (firstValue.isEmpty()) {
            for (String str : restrictions) {
                str = "access:" + str;
                if (way.hasTag(str)) {
                    firstValue = way.getTag(str);
                    break;
                }
            }
        }

        // sometimes bridges have height tags and mean not the height limitation but the height of the bridge https://discuss.graphhopper.com/t/1468
        boolean isBridge = way.hasTag("bridge");
        if (!isBridge && (smaller(way, "maxheight", height) || smaller(way, "maxheight:physical", height)
                || smaller(way, "height", height + 0.3))) {
            return EncodingManager.Access.CAN_SKIP;
        }

        // plus a bit for mirrors
        double tmpWidth = width + mirrorWidth;
        if (smaller(way, "maxwidth", tmpWidth) || smaller(way, "maxwidth:physical", tmpWidth)
                || smaller(way, "width", tmpWidth + 0.05)) {
            return EncodingManager.Access.CAN_SKIP;
        }

        if (smaller(way, "maxlength", length) || isHGV && smaller(way, "maxlength:hgv", length)) {
            return EncodingManager.Access.CAN_SKIP;
        }

        double tmpWeight = weight;
        // See http://wiki.openstreetmap.org/wiki/Proposed_features/gross_weight
        if (smallerWeight(way, "maxweight", tmpWeight) || smallerWeight(way, "maxgcweight", tmpWeight)) {
            if (!getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                return EncodingManager.Access.CAN_SKIP;
        }

        tmpWeight = weight / axes;
        if (smallerWeight(way, "maxaxleload", tmpWeight)) {
            return EncodingManager.Access.CAN_SKIP;
        }

        // if it is not a car then "motorcar" restriction is removed. But we should still exclude the way if motorcar is disallowed, see testMotorcar
        if (!isCarLike() && way.hasTag("motorcar", "no"))
            return EncodingManager.Access.CAN_SKIP;

        if (!firstValue.isEmpty()) {
            if (restrictedValues.contains(firstValue) && !getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                return EncodingManager.Access.CAN_SKIP;

            if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way)) {
                return EncodingManager.Access.CAN_SKIP;
            }

            // TODO lane handling firstValue.contains("|") -> but necessary? There is at least one 'yes' if explicitely tagged
            if (intendedValues.contains(firstValue))
                return EncodingManager.Access.WAY;
        }

        // do not drive street cars into fords
        if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford"))) {
            return EncodingManager.Access.CAN_SKIP;
        }

        if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way)) {
            return EncodingManager.Access.CAN_SKIP;
        } else {
            return EncodingManager.Access.WAY;
        }
    }

    @Override
    protected double getSpeed(ReaderWay way) {
        return super.getSpeed(way);
    }

    @Override
    protected double applyMaxSpeed(ReaderWay way, double speed) {
        // pick max speed as it is. reduce it in SpeedModel and not by a constant factor of 0.9 like done in CarFlagEncoder
        double maxSpeed = getMaxSpeed(way);
        if (maxSpeed >= 0) {
            return Math.max(5, Math.min(maxPossibleSpeed, maxSpeed));
        }
        return speed;
    }

    public boolean smallerWeight(ReaderWay way, String key, double val) {
        String value = way.getTag(key, "");
        if (OSMValueExtractor.isInvalidValue(value))
            return false;
        try {
            double convVal = OSMValueExtractor.stringToTons(value);
            return convVal < val;
        } catch (Exception ex) {
            logger.warn("ID:" + way.getId() + " invalid weight value " + value);
        }

        return false;
    }

    public boolean smaller(ReaderWay way, String key, double val) {
        String value = way.getTag(key, "");
        if (OSMValueExtractor.isInvalidValue(value))
            return false;
        try {
            double convVal = OSMValueExtractor.stringToMeter(value);
            return convVal < val;
        } catch (Exception ex) {
            logger.warn("ID:" + way.getId() + " invalid length, width or height value " + value);
        }

        return false;
    }

    @Override
    public int getVersion() {
        return 4;
    }

    @Override
    public String toString() {
        return name;
    }
}

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
package com.graphhopper.http;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.ConditionalOSMTagInspector;
import com.graphhopper.reader.osm.conditional.ConditionalParser;
import com.graphhopper.reader.osm.conditional.ConditionalValueParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.CarTagParser;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.WayAccess;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.graphhopper.routing.util.EncodingManager.getKey;
import static com.graphhopper.util.Helper.toLowerCase;

/**
 * Truck encoder with various properties. See truck limits bugs in OSM e.g.
 * maxheights etc: http://maxheight.bplaced.net/overpass/map.html
 * <p>
 *
 * @author Peter Karich
 */
public class TruckTagParser extends CarTagParser {
    public static final double EE_TRUCK_MAX_SPEED = 95;

    /**
     * Describes a big HGV truck with 3 axes. E.g. the 6 wheeler here:
     * http://www.grabtrucks.com/willitfit/ where we only increased the length
     */
    public static TruckTagParser createTruck(EncodedValueLookup lookup, PMap properties) {
        if (!properties.has("name"))
            properties = new PMap(properties).putObject("name", "truck");
        if (!properties.has("max_speed"))
            properties = new PMap(properties).putObject("max_speed", EE_TRUCK_MAX_SPEED);
        return new TruckTagParser(lookup, properties).
                setHeight(3.7).setWidth(2.6, 0.34).setLength(12).
                setWeight(13.0 + 13.0).setAxes(3).setIsHGV(true).
                initProperties();
    }

    // Describes "small truck" - eg a delivery vehicle
    public static TruckTagParser createSmallTruck(EncodedValueLookup lookup, PMap properties) {
        if (!properties.has("name"))
            properties = new PMap(properties).putObject("name", "small_truck");
        if (!properties.has("max_speed"))
            properties = new PMap(properties).putObject("max_speed", EE_TRUCK_MAX_SPEED);
        return new TruckTagParser(lookup, properties).
                setHeight(2.7D).setWidth(2.0D, 0.4D).setLength(5.5D).
                setWeight(3.48D).setIsHGV(false).
                initProperties();
    }

    // Unused function showing example of customizing various vehicle parameters
    public static TruckTagParser createCustomEE(EncodedValueLookup lookup, PMap properties) {
        if (!properties.has("name"))
            throw new IllegalArgumentException("custom_ee requires a name");
        if (!properties.has("max_speed"))
            throw new IllegalArgumentException("custom_ee requires max_speed");
        if (properties.getBool("soft_oneway", false))
            throw new IllegalArgumentException("soft_oneway is no longer supported. Use roads FlagEncoder with car_access instead");
        return new TruckTagParser(lookup, properties).
                setHeight(properties.getDouble("height", 2.7)).
                setWidth(properties.getDouble("width", 2), properties.getDouble("mirror_width", 0.4)).
                setLength(properties.getDouble("length", 5.5)).
                setWeight(properties.getDouble("weight", 2.08)).
                setIsHGV(properties.getBool("hgv", false)).
                setAxes(properties.getInt("axes", 2)).
                setCarriesGoods(properties.getBool("carries_goods", false)).
                setCarriesHazard(properties.getBool("carries_hazard", false)).
                setIsAgricultural(properties.getBool("agricultural", false)).
                setIsTaxi(properties.getBool("taxi", false)).
                setExcludeMaxSpeed(properties.getDouble("exclude_max_speed", 0)).
                initProperties(createSpeedMap(properties.getString("speed", "")));
    }

    // small_truck|custom_ee=true|speed=primary=30;secondary=20
    private static Map<String, Integer> createSpeedMap(String semicolonStr) {
        Map<String, Integer> map = new HashMap<>();
        for (String arg : semicolonStr.split(";")) {
            int index = arg.indexOf("=");
            if (index <= 0)
                continue;

            String key = arg.substring(0, index);
            String value = arg.substring(index + 1);
            Integer integ = map.put(key, Integer.parseInt(value));
            if (integ != null)
                throw new IllegalArgumentException("Pair '" + toLowerCase(key) + "'='" + value + "' not possible to " +
                        "add to the speed map as the key already exists with '" + integ + "'");
        }
        return map;
    }

    private static final Logger logger = LoggerFactory.getLogger(TruckTagParser.class);
    // default settings "isCarLike == true"
    private boolean isHGV = false;
    private boolean carriesGoods = true;
    private boolean carriesHazard = false;
    private boolean isAgricultural = false;
    private boolean isTaxi = false;
    private boolean isPSV = false;
    private double length = 4.5;
    private double width = 2;
    private double mirrorWidth = 0;
    private double height = 2;
    private double weight = 2.5;
    private double excludeMaxSpeed;
    private int axes = 2;

    public TruckTagParser(EncodedValueLookup lookup, PMap properties) {
        this(
                lookup.getBooleanEncodedValue(getKey(properties.getString("name", "car"), "access")),
                lookup.getDecimalEncodedValue(getKey(properties.getString("name", "car"), "average_speed")),
                lookup.getBooleanEncodedValue(Roundabout.KEY),
                properties
        );
    }

    public TruckTagParser(BooleanEncodedValue accessEnc, DecimalEncodedValue speedEnc,
                          BooleanEncodedValue roundaboutEnc, PMap properties) {
        super(accessEnc, speedEnc, roundaboutEnc, properties, TransportationMode.CAR,
                speedEnc.getNextStorableValue(properties.getDouble("max_speed", EE_TRUCK_MAX_SPEED)));
        if (!properties.getBool("block_private", true)) {
            restrictedValues.remove("private");
            intendedValues.add("private");
            intendedValues.add("destination");
        }
        if (!properties.getBool("block_delivery", true)) {
            restrictedValues.remove("delivery");
            intendedValues.add("delivery");
        }
        if (!properties.getBool("block_access", true)) {
            restrictedValues.remove("no");
            // intendedValues.add("no");
        }
    }

    public TruckTagParser setHeight(double height) {
        this.height = height;
        return this;
    }

    public TruckTagParser setLength(double length) {
        this.length = length;
        return this;
    }

    public TruckTagParser setWidth(double width, double mirrorWidth) {
        this.width = width;
        this.mirrorWidth = mirrorWidth;
        return this;
    }

    public TruckTagParser setAxes(int axes) {
        this.axes = axes;
        return this;
    }

    /**
     * Sets the weight of the vehicle including people, equipment and payload in tons
     */
    public TruckTagParser setWeight(double weight) {
        this.weight = weight;
        return this;
    }

    /**
     * Sets if this vehicle should be a heavy goods vehicle
     */
    public TruckTagParser setIsHGV(boolean isHGV) {
        this.isHGV = isHGV;
        return this;
    }

    public boolean isHGV() {
        return isHGV;
    }

    public TruckTagParser setCarriesGoods(boolean carriesGoods) {
        this.carriesGoods = carriesGoods;
        return this;
    }

    public TruckTagParser setCarriesHazard(boolean carriesHazard) {
        this.carriesHazard = carriesHazard;
        return this;
    }

    public TruckTagParser setIsAgricultural(boolean isAgricultural) {
        this.isAgricultural = isAgricultural;
        return this;
    }

    public TruckTagParser setIsTaxi(boolean isTaxi) {
        this.isTaxi = isTaxi;
        this.isPSV = isTaxi;
        return this;
    }

    public TruckTagParser setExcludeMaxSpeed(double maxSpeed) {
        excludeMaxSpeed = maxSpeed;
        return this;
    }

    public TruckTagParser initProperties() {
        // TODO merge with init somehow?
        return initProperties(null);
    }

    /**
     * This method initialized the speed and the specified speedMap or car
     * speeds will be used for default speeds. Maps highway tags to speeds.
     */
    public TruckTagParser initProperties(Map<String, Integer> speedMap) {
        final List<String> tmpRestrictions = new ArrayList<>();
        if (isCarLike()) {
            trackTypeSpeedMap.clear();
            trackTypeSpeedMap.put("grade2", 12);
            trackTypeSpeedMap.put("grade1", 14);
            trackTypeSpeedMap.put(null, 8);

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
        } else if (weight <= 10.0 && width <= 2.7) {
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
            barriers.remove("bus_trap");
            barriers.remove("sump_buster");

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

        if (isPSV) {
            if (isTaxi)
                tmpRestrictions.add("taxi");
            tmpRestrictions.add("psv");
            // tricky. a taxi is usually a motorcar but not always
            // restrictions.remove("motorcar");

        } else if (isHGV) {
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
    public void init(DateRangeParser dateRangeParser) {
        super.init(dateRangeParser);

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

        setConditionalTagInspector(condInspector);
    }

    boolean isCarLike() {
        return weight <= 3.0 && width <= 2 && length < 6;
    }

    double getDefaultSpeed(String key) {
        return defaultSpeedMap.get(key);
    }

    @Override
    public WayAccess getAccess(ReaderWay way) {
        if (maxPossibleSpeed < 1)
            throw new IllegalStateException("maxPossibleSpeed cannot be smaller 1 but was " + maxPossibleSpeed + ". Call initProperties before using TruckFlagEncoder");

        String highwayValue = way.getTag("highway");
        String firstValue = way.getFirstPriorityTag(restrictions);
        if (highwayValue == null) {
            if (way.hasTag("route", ferries)) {
                if (restrictedValues.contains(firstValue))
                    return WayAccess.CAN_SKIP;
                if (intendedValues.contains(firstValue) ||
                        // implied default is allowed only if foot and bicycle is not specified:
                        firstValue.isEmpty() && !way.hasTag("foot") && !way.hasTag("bicycle") ||
                        // if hgv is allowed than smaller trucks and even cars are too, see #30
                        way.hasTag("hgv", "yes"))
                    return WayAccess.FERRY;
            }
            return WayAccess.CAN_SKIP;
        }

        if ("track".equals(highwayValue) && trackTypeSpeedMap.get(way.getTag("tracktype")) == null)
            return WayAccess.CAN_SKIP;

        if (!defaultSpeedMap.containsKey(highwayValue)) {
            return WayAccess.CAN_SKIP;
        }

        if (excludeMaxSpeed > 0 && getMaxSpeed(way) > excludeMaxSpeed) {
            return WayAccess.CAN_SKIP;
        }

        if (way.hasTag("impassable", "yes") || way.hasTag("status", "impassable")) {
            return WayAccess.CAN_SKIP;
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
            return WayAccess.CAN_SKIP;
        }

        // plus a bit for mirrors
        double tmpWidth = width + mirrorWidth;
        if (smaller(way, "maxwidth", tmpWidth) || smaller(way, "maxwidth:physical", tmpWidth)
                || smaller(way, "width", tmpWidth + 0.05)) {
            return WayAccess.CAN_SKIP;
        }

        if (smaller(way, "maxlength", length) || isHGV && smaller(way, "maxlength:hgv", length)) {
            return WayAccess.CAN_SKIP;
        }

        double tmpWeight = weight;
        // See http://wiki.openstreetmap.org/wiki/Proposed_features/gross_weight
        if (smallerWeight(way, "maxweight", tmpWeight) || smallerWeight(way, "maxgcweight", tmpWeight)) {
            if (!getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                return WayAccess.CAN_SKIP;
        }

        tmpWeight = weight / axes;
        if (smallerWeight(way, "maxaxleload", tmpWeight)) {
            return WayAccess.CAN_SKIP;
        }

        if (!firstValue.isEmpty()) {
            if (restrictedValues.contains(firstValue) && !getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                return WayAccess.CAN_SKIP;

            if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way)) {
                return WayAccess.CAN_SKIP;
            }

            // TODO lane handling firstValue.contains("|") -> but necessary? There is at least one 'yes' if explicitely tagged
            if (intendedValues.contains(firstValue))
                return WayAccess.WAY;
        }

        // do not drive street cars into fords
        if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford"))) {
            return WayAccess.CAN_SKIP;
        }

        if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way)) {
            return WayAccess.CAN_SKIP;
        } else {
            return WayAccess.WAY;
        }
    }

    @Override
    protected double getSpeed(ReaderWay way) {
        double speed = super.getSpeed(way);

        double boost = 0;
        if (isPSV) {
            if (way.hasTag("lanes:psv")) {
                boost = 4;
            }

            if (isTaxi && way.hasTag("lanes:taxi")) {
                boost = 4;
            }
        }

        return speed + boost;
    }

    @Override
    protected boolean isBackwardOneway(ReaderWay way) {
        return super.isBackwardOneway(way)
                // the tag lanes:psv:backward can contain a positive number
                || isPSV && (way.hasTag("oneway:psv", "no") || way.hasTag("psv", "opposite_lane") || way.hasTag("lanes:psv:backward") || way.hasTag("psv:lanes:backward") || way.hasTag("psv:backward", "yes"))
                || isPSV && isTaxi && (way.hasTag("oneway:taxi", "no") || way.hasTag("taxi", "opposite_lane") || way.hasTag("lanes:taxi:backward") || way.hasTag("taxi:lanes:backward") || way.hasTag("taxi:backward", "yes"));
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

}

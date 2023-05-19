/*
 * Copyright 2014-2023 GraphHopper GmbH.
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
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.Roundabout;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.WayAccess;
import com.graphhopper.routing.util.parsers.AbstractAccessParser;
import com.graphhopper.routing.util.parsers.CarAccessParser;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.graphhopper.routing.util.parsers.AbstractAverageSpeedParser.getMaxSpeed;

public class TruckAccessParser extends CarAccessParser {

    private static final Logger logger = LoggerFactory.getLogger(TruckAccessParser.class);
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

    public TruckAccessParser(EncodedValueLookup lookup, PMap properties) {
        this(
                lookup.getBooleanEncodedValue(VehicleAccess.key(properties.getString("name", "car"))),
                lookup.getBooleanEncodedValue(Roundabout.KEY),
                properties
        );
    }

    public TruckAccessParser(BooleanEncodedValue accessEnc, BooleanEncodedValue roundaboutEnc, PMap properties) {
        super(accessEnc, roundaboutEnc, properties, TransportationMode.CAR);
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

    public TruckAccessParser setHeight(double height) {
        this.height = height;
        return this;
    }

    public TruckAccessParser setLength(double length) {
        this.length = length;
        return this;
    }

    public TruckAccessParser setWidth(double width, double mirrorWidth) {
        this.width = width;
        this.mirrorWidth = mirrorWidth;
        return this;
    }

    public TruckAccessParser setAxes(int axes) {
        this.axes = axes;
        return this;
    }

    /**
     * Sets the weight of the vehicle including people, equipment and payload in tons
     */
    public TruckAccessParser setWeight(double weight) {
        this.weight = weight;
        return this;
    }

    /**
     * Sets if this vehicle should be a heavy goods vehicle
     */
    public TruckAccessParser setIsHGV(boolean isHGV) {
        this.isHGV = isHGV;
        return this;
    }

    public boolean isHGV() {
        return isHGV;
    }

    public TruckAccessParser setCarriesGoods(boolean carriesGoods) {
        this.carriesGoods = carriesGoods;
        return this;
    }

    public TruckAccessParser setCarriesHazard(boolean carriesHazard) {
        this.carriesHazard = carriesHazard;
        return this;
    }

    public TruckAccessParser setIsAgricultural(boolean isAgricultural) {
        this.isAgricultural = isAgricultural;
        return this;
    }

    public TruckAccessParser setIsTaxi(boolean isTaxi) {
        this.isTaxi = isTaxi;
        this.isPSV = isTaxi;
        return this;
    }

    public TruckAccessParser setExcludeMaxSpeed(double maxSpeed) {
        excludeMaxSpeed = maxSpeed;
        return this;
    }

    public TruckAccessParser initProperties() {
        // TODO merge with init somehow?
        return initProperties(null);
    }

    /**
     * This method initialized the speed and the specified speedMap or car
     * speeds will be used for default speeds. Maps highway tags to speeds.
     */
    public TruckAccessParser initProperties(Map<String, Integer> speedMap) {
        final List<String> tmpRestrictions = new ArrayList<>();
        if (isCarLike()) {
            trackTypeValues.clear();
            trackTypeValues.addAll(Arrays.asList("grade2", "grade1", null));

        } else if (isAgricultural) {
            highwayValues.add("forestry");
            highwayValues.remove("track");

            restrictions.remove("motorcar");
            tmpRestrictions.add("agricultural");
        } else {
            highwayValues.remove("track");
            restrictions.remove("motorcar");
        }

        // make width and weight dependent (currently three categories)
        if (isCarLike()) {
            // keep values
        } else if (weight <= 10.0 && width <= 2.7) {
            // keep values (small truck)

        } else {
            // wide enough trucks can pass this trap
            barriers.remove("bus_trap");
            barriers.remove("sump_buster");
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
                if (entry.getValue() < 0) {
                    highwayValues.remove(entry.getKey());
                } else {
                    highwayValues.add(entry.getKey());
                }
            }
        } else {
            // use car defaults
        }

        return this;
    }

    @Override
    public AbstractAccessParser init(DateRangeParser dateRangeParser) {
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
        return this;
    }

    boolean isCarLike() {
        return weight <= 3.0 && width <= 2 && length < 6;
    }

    @Override
    public WayAccess getAccess(ReaderWay way) {
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

        if ("track".equals(highwayValue) && !trackTypeValues.contains(way.getTag("tracktype")))
            return WayAccess.CAN_SKIP;

        if (!highwayValues.contains(highwayValue)) {
            return WayAccess.CAN_SKIP;
        }

        if (excludeMaxSpeed > 0 && Math.max(getMaxSpeed(way, false), getMaxSpeed(way, true)) > excludeMaxSpeed) {
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
    protected boolean isBackwardOneway(ReaderWay way) {
        return super.isBackwardOneway(way)
                // the tag lanes:psv:backward can contain a positive number
                || isPSV && (way.hasTag("oneway:psv", "no") || way.hasTag("psv", "opposite_lane") || way.hasTag("lanes:psv:backward") || way.hasTag("psv:lanes:backward") || way.hasTag("psv:backward", "yes"))
                || isPSV && isTaxi && (way.hasTag("oneway:taxi", "no") || way.hasTag("taxi", "opposite_lane") || way.hasTag("lanes:taxi:backward") || way.hasTag("taxi:lanes:backward") || way.hasTag("taxi:backward", "yes"));
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

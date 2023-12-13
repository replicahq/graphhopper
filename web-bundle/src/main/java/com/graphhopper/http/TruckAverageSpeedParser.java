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

import com.google.common.collect.ImmutableMap;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.parsers.CarAverageSpeedParser;
import com.graphhopper.util.PMap;

import java.util.Map;


/**
 * Truck speed parser with various properties. See truck limits bugs in OSM e.g.
 * maxheights etc: http://maxheight.bplaced.net/overpass/map.html
 * <p>
 *
 * @author Peter Karich
 */
public class TruckAverageSpeedParser extends CarAverageSpeedParser {
    public static final double EE_SMALL_TRUCK_MAX_SPEED = 106;
    public static final double EE_TRUCK_MAX_SPEED = 96;
    // Assume 50 for scooter. This is a bit faster than Germany and Austria with 45, but other countries have higher limits...
    public static final double EE_SCOOTER_SPEED = 50;

    public static final double SMALL_TRUCK_WEIGHT = 2.08 + 1.4;

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

    // empty if no custom speeds were provided
    private final ImmutableMap<Long, Double> osmWayIdToCustomMaxSpeed;

    public TruckAverageSpeedParser(EncodedValueLookup lookup, PMap properties, ImmutableMap<Long, Double> osmWayIdToCustomMaxSpeed) {
        this(
                lookup.getDecimalEncodedValue(VehicleSpeed.key(properties.getString("name", "car"))),
                properties,
                osmWayIdToCustomMaxSpeed
        );
    }

    public TruckAverageSpeedParser(DecimalEncodedValue speedEnc, PMap properties, ImmutableMap<Long, Double> osmWayIdToCustomMaxSpeed) {
        super(speedEnc, speedEnc.getNextStorableValue(properties.getDouble("max_speed", CarAverageSpeedParser.CAR_MAX_SPEED)));
        this.osmWayIdToCustomMaxSpeed = osmWayIdToCustomMaxSpeed;
    }

    public TruckAverageSpeedParser setHeight(double height) {
        this.height = height;
        return this;
    }

    public TruckAverageSpeedParser setLength(double length) {
        this.length = length;
        return this;
    }

    public TruckAverageSpeedParser setWidth(double width, double mirrorWidth) {
        this.width = width;
        this.mirrorWidth = mirrorWidth;
        return this;
    }

    public TruckAverageSpeedParser setAxes(int axes) {
        this.axes = axes;
        return this;
    }

    /**
     * Sets the weight of the vehicle including people, equipment and payload in tons
     */
    public TruckAverageSpeedParser setWeight(double weight) {
        this.weight = weight;
        return this;
    }

    /**
     * Sets if this vehicle should be a heavy goods vehicle
     */
    public TruckAverageSpeedParser setIsHGV(boolean isHGV) {
        this.isHGV = isHGV;
        return this;
    }

    public boolean isHGV() {
        return isHGV;
    }

    public TruckAverageSpeedParser setCarriesGoods(boolean carriesGoods) {
        this.carriesGoods = carriesGoods;
        return this;
    }

    public TruckAverageSpeedParser setCarriesHazard(boolean carriesHazard) {
        this.carriesHazard = carriesHazard;
        return this;
    }

    public TruckAverageSpeedParser setIsAgricultural(boolean isAgricultural) {
        this.isAgricultural = isAgricultural;
        return this;
    }

    public TruckAverageSpeedParser setIsTaxi(boolean isTaxi) {
        this.isTaxi = isTaxi;
        this.isPSV = isTaxi;
        return this;
    }

    public TruckAverageSpeedParser setExcludeMaxSpeed(double maxSpeed) {
        excludeMaxSpeed = maxSpeed;
        return this;
    }

    public TruckAverageSpeedParser initProperties() {
        // TODO merge with init somehow?
        return initProperties(null);
    }

    /**
     * This method initialized the speed and the specified speedMap or car
     * speeds will be used for default speeds. Maps highway tags to speeds.
     */
    public TruckAverageSpeedParser initProperties(Map<String, Integer> speedMap) {
        if (isCarLike()) {
            trackTypeSpeedMap.clear();
            trackTypeSpeedMap.put("grade2", 12);
            trackTypeSpeedMap.put("grade1", 14);
            trackTypeSpeedMap.put(null, 8);

        } else if (isAgricultural) {
            defaultSpeedMap.put("forestry", 15);
            defaultSpeedMap.remove("track");
        } else {
            defaultSpeedMap.remove("track");
        }

        // make width and weight dependent (currently three categories)
        if (isCarLike()) {
            // keep values
        } else if (weight <= 10.0 && width <= 2.7) {
            // small truck
            defaultSpeedMap.put("motorway", 90);
            defaultSpeedMap.put("motorway_link", 75);
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
            // truck / bus
            defaultSpeedMap.put("motorway", 80);
            defaultSpeedMap.put("motorway_link", 75);
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

        if (speedMap != null) {
            for (Map.Entry<String, Integer> entry : speedMap.entrySet()) {
                if (entry.getValue() < 0) {
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

    boolean isCarLike() {
        return weight <= 3.0 && width <= 2 && length < 6;
    }

    double getDefaultSpeed(String key) {
        return defaultSpeedMap.get(key);
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
    protected double applyMaxSpeed(ReaderWay way, double speed, boolean bwd) {
        return CustomSpeedsUtils.getCustomMaxSpeed(way, osmWayIdToCustomMaxSpeed).orElseGet(() -> {
            // pick max speed as it is. reduce it in SpeedModel and not by a constant factor of 0.9 like done in superclass
            double maxSpeed = getMaxSpeed(way, bwd);
            if (maxSpeed >= 0) {
                return Math.max(5, Math.min(maxPossibleSpeed, maxSpeed));
            }
            return speed;
        });
    }

    @Override
    protected double applyBadSurfaceSpeed(ReaderWay way, double speed) {
        return CustomSpeedsUtils.getCustomBadSurfaceSpeed(way, osmWayIdToCustomMaxSpeed).orElseGet(() -> super.applyBadSurfaceSpeed(way, speed));
    }
}

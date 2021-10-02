package com.graphhopper.replica;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.ConditionalOSMTagInspector;
import com.graphhopper.reader.osm.conditional.ConditionalParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.reader.osm.conditional.ConditionalValueParser.ConditionState;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager.Access;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.util.PMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TruckFlagEncoder extends CarFlagEncoder {
    public static final double SMALL_TRUCK_WEIGHT = 3.48D;
    private static final Logger logger = LoggerFactory.getLogger(TruckFlagEncoder.class);
    private final String name;
    private boolean isHGV = false;
    private boolean carriesGoods = true;
    private boolean carriesHazard = false;
    private boolean isAgricultural = false;
    private double length = 4.5D;
    private double width = 2.0D;
    private double mirrorWidth = 0.0D;
    private double height = 2.0D;
    private double weight = 2.5D;
    private double excludeMaxSpeed;
    private int axes = 2;

    public static TruckFlagEncoder createCar(PMap properties, String name) {
        setDefaultProperties(properties, 7, 2.0D, 1);
        return (new TruckFlagEncoder(properties, name)).initProperties();
    }

    public static TruckFlagEncoder createVan(String name) {
        return createVan(new PMap(), name);
    }

    public static TruckFlagEncoder createVan(PMap properties, String name) {
        setDefaultProperties(properties, 7, 2.0D, 1);
        return (new TruckFlagEncoder(properties, name)).setHeight(2.5D).setWidth(1.91D, 0.4D).setLength(4.75D).setWeight(2.77D).initProperties();
    }

    public static TruckFlagEncoder createSmallTruck(String name) {
        return createSmallTruck(new PMap(), name);
    }

    public static TruckFlagEncoder createSmallTruck(PMap properties, String name) {
        setDefaultProperties(properties, 7, 2.0D, 1);
        return (new TruckFlagEncoder(properties, name)).setHeight(2.7D).setWidth(2.0D, 0.4D).setLength(5.5D).setWeight(3.48D).initProperties();
    }

    public static TruckFlagEncoder createTruck(String name) {
        return createTruck(new PMap(), name);
    }

    public static TruckFlagEncoder createTruck(PMap properties, String name) {
        setDefaultProperties(properties, 6, 2.0D, 1);
        return (new TruckFlagEncoder(properties, name)).setHeight(3.7D).setWidth(2.6D, 0.34D).setLength(12.0D).setWeight(26.0D).setAxes(3).setIsHGV(true).initProperties();
    }

    private static void setDefaultProperties(PMap properties, int speedBits, double speedFactor, int maxTurnCosts) {
        if (!properties.has("speed_bits")) {
            properties.putObject("speed_bits", speedBits);
        }

        if (!properties.has("speed_factor")) {
            properties.putObject("speed_factor", speedFactor);
        }

        if (!properties.has("turn_costs") && !properties.has("max_turn_costs")) {
            properties.putObject("max_turn_costs", maxTurnCosts);
        }

    }

    public TruckFlagEncoder(PMap properties, String profileName) {
        super(properties);
        if (!properties.getBool("block_private", true)) {
            this.restrictedValues.remove("private");
            this.intendedValues.add("private");
            this.intendedValues.add("destination");
        }

        if (!properties.getBool("block_delivery", true)) {
            this.restrictedValues.remove("delivery");
            this.intendedValues.add("delivery");
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

    public TruckFlagEncoder setWeight(double weight) {
        this.weight = weight;
        return this;
    }

    public TruckFlagEncoder setIsHGV(boolean isHGV) {
        this.isHGV = isHGV;
        return this;
    }

    public boolean isHGV() {
        return this.isHGV;
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
        this.maxPossibleSpeed = maxSpeed;
        return this;
    }

    public TruckFlagEncoder setExcludeMaxSpeed(double maxSpeed) {
        this.excludeMaxSpeed = maxSpeed;
        return this;
    }

    public TruckFlagEncoder initProperties() {
        return this.initProperties((Map)null);
    }

    public TruckFlagEncoder initProperties(Map<String, Integer> speedMap) {
        if (this.maxPossibleSpeed <= 0) {
            if (this.isCarLike()) {
                this.maxPossibleSpeed = 140;
            } else if (this.isHGV) {
                this.maxPossibleSpeed = 95;
            } else {
                this.maxPossibleSpeed = 105;
            }
        }

        List<String> tmpRestrictions = new ArrayList();
        if (this.isCarLike()) {
            this.trackTypeSpeedMap.clear();
            this.trackTypeSpeedMap.put("grade1", 15);
            this.trackTypeSpeedMap.put(null, 15);
        } else if (this.isAgricultural) {
            this.defaultSpeedMap.put("forestry", 15);
            this.defaultSpeedMap.remove("track");
            this.restrictions.remove("motorcar");
            tmpRestrictions.add("agricultural");
        } else {
            this.defaultSpeedMap.remove("track");
            this.restrictions.remove("motorcar");
        }

        if (!this.isCarLike()) {
            if (this.weight <= 10.0D && this.width <= 2.7D) {
                this.defaultSpeedMap.put("motorway", 90);
                this.defaultSpeedMap.put("motorway_link", 75);
                this.defaultSpeedMap.put("motorroad", 75);
                this.defaultSpeedMap.put("trunk", 70);
                this.defaultSpeedMap.put("trunk_link", 60);
                this.defaultSpeedMap.put("primary", 65);
                this.defaultSpeedMap.put("primary_link", 60);
                this.defaultSpeedMap.put("secondary", 55);
                this.defaultSpeedMap.put("secondary_link", 50);
                this.defaultSpeedMap.put("tertiary", 30);
                this.defaultSpeedMap.put("tertiary_link", 25);
                this.defaultSpeedMap.put("unclassified", 25);
                this.defaultSpeedMap.put("residential", 20);
                this.defaultSpeedMap.put("living_street", 5);
                this.defaultSpeedMap.put("service", 15);
                this.defaultSpeedMap.put("road", 15);
            } else {
                this.defaultSpeedMap.put("motorway", 80);
                this.defaultSpeedMap.put("motorway_link", 75);
                this.defaultSpeedMap.put("motorroad", 70);
                this.defaultSpeedMap.put("trunk", 70);
                this.defaultSpeedMap.put("trunk_link", 60);
                this.defaultSpeedMap.put("primary", 60);
                this.defaultSpeedMap.put("primary_link", 55);
                this.defaultSpeedMap.put("secondary", 55);
                this.defaultSpeedMap.put("secondary_link", 55);
                this.defaultSpeedMap.put("tertiary", 20);
                this.defaultSpeedMap.put("tertiary_link", 20);
                this.defaultSpeedMap.put("unclassified", 20);
                this.defaultSpeedMap.put("residential", 15);
                this.defaultSpeedMap.put("living_street", 5);
                this.defaultSpeedMap.put("service", 10);
                this.defaultSpeedMap.put("road", 10);
            }
        }

        if (this.carriesHazard) {
            tmpRestrictions.add("hazmat");
        }

        if (this.isHGV) {
            tmpRestrictions.add("hgv");
            tmpRestrictions.add("goods");
        } else if (this.carriesGoods) {
            tmpRestrictions.add("goods");
        }

        tmpRestrictions.addAll(this.restrictions);
        this.restrictions.clear();
        this.restrictions.addAll(tmpRestrictions);
        if (speedMap != null) {
            Iterator var3 = speedMap.entrySet().iterator();

            while(var3.hasNext()) {
                Entry<String, Integer> entry = (Entry)var3.next();
                if ((Integer)entry.getValue() <= 0) {
                    this.defaultSpeedMap.remove(entry.getKey());
                } else {
                    this.defaultSpeedMap.put((String)entry.getKey(), (Integer)entry.getValue());
                }
            }
        }

        return this;
    }

    protected void init(DateRangeParser dateRangeParser) {
        List<String> tmpRestrictions = new ArrayList(this.restrictions);
        Set<String> tmpIntendedValues = new HashSet(this.intendedValues);
        boolean specialAccess = this.intendedValues.contains("delivery") || this.intendedValues.contains("destination") || this.intendedValues.contains("private");
        if (specialAccess) {
            tmpRestrictions.add("maxweight");
            tmpRestrictions.add("maxheight");
            tmpRestrictions.add("maxwidth");
            tmpIntendedValues.add("none");
        }

        ConditionalOSMTagInspector condInspector = new ConditionalOSMTagInspector(Collections.emptyList(), tmpRestrictions, this.restrictedValues, tmpIntendedValues, false);
        condInspector.addValueParser(new DateRangeParser(DateRangeParser.createCalendar()));
        condInspector.addValueParser(ConditionalParser.createNumberParser("weight", this.weight));
        condInspector.addValueParser(ConditionalParser.createNumberParser("height", this.height));
        condInspector.addValueParser(ConditionalParser.createNumberParser("width", this.width));
        condInspector.addValueParser((conditional) -> {
            return tmpRestrictions.contains(conditional) ? ConditionState.TRUE : ConditionState.INVALID;
        });
        if (specialAccess) {
            condInspector.addValueParser((conditional) -> {
                return this.intendedValues.contains(conditional) ? ConditionState.TRUE : ConditionState.INVALID;
            });
        }

        super.setConditionalTagInspector(condInspector);
    }

    boolean isCarLike() {
        return this.weight <= 3.0D && this.width <= 2.0D && this.length < 6.0D;
    }

    double getDefaultSpeed(String key) {
        return (double)(Integer)this.defaultSpeedMap.get(key);
    }

    public Access getAccess(ReaderWay way) {
        if (this.maxPossibleSpeed < 1) {
            throw new IllegalStateException("maxPossibleSpeed cannot be smaller 1 but was " + this.maxPossibleSpeed);
        } else {
            String highwayValue = way.getTag("highway");
            String firstValue = way.getFirstPriorityTag(this.restrictions);
            if (highwayValue == null) {
                if (way.hasTag("route", this.ferries)) {
                    if (this.restrictedValues.contains(firstValue)) {
                        return Access.CAN_SKIP;
                    }

                    if (this.intendedValues.contains(firstValue) || firstValue.isEmpty() && !way.hasTag("foot", new String[0]) && !way.hasTag("bicycle", new String[0]) || way.hasTag("hgv", "yes")) {
                        return Access.FERRY;
                    }
                }

                return Access.CAN_SKIP;
            } else if ("track".equals(highwayValue) && this.trackTypeSpeedMap.get(way.getTag("tracktype")) == null) {
                return Access.CAN_SKIP;
            } else if (!this.defaultSpeedMap.containsKey(highwayValue)) {
                return Access.CAN_SKIP;
            } else if (this.excludeMaxSpeed > 0.0D && this.getMaxSpeed(way) > this.excludeMaxSpeed) {
                return Access.CAN_SKIP;
            } else if (!way.hasTag("impassable", "yes") && !way.hasTag("status", "impassable")) {
                if (firstValue.isEmpty()) {
                    Iterator var4 = this.restrictions.iterator();

                    while(var4.hasNext()) {
                        String str = (String)var4.next();
                        str = "access:" + str;
                        if (way.hasTag(str, new String[0])) {
                            firstValue = way.getTag(str);
                            break;
                        }
                    }
                }

                boolean isBridge = way.hasTag("bridge", new String[0]);
                if (!isBridge && (this.smaller(way, "maxheight", this.height) || this.smaller(way, "maxheight:physical", this.height) || this.smaller(way, "height", this.height + 0.3D))) {
                    return Access.CAN_SKIP;
                } else {
                    double tmpWidth = this.width + this.mirrorWidth;
                    if (!this.smaller(way, "maxwidth", tmpWidth) && !this.smaller(way, "maxwidth:physical", tmpWidth) && !this.smaller(way, "width", tmpWidth + 0.05D)) {
                        if (this.smaller(way, "maxlength", this.length) || this.isHGV && this.smaller(way, "maxlength:hgv", this.length)) {
                            return Access.CAN_SKIP;
                        } else {
                            double tmpWeight = this.weight;
                            if ((this.smallerWeight(way, "maxweight", tmpWeight) || this.smallerWeight(way, "maxgcweight", tmpWeight)) && !this.getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way)) {
                                return Access.CAN_SKIP;
                            } else {
                                tmpWeight = this.weight / (double)this.axes;
                                if (this.smallerWeight(way, "maxaxleload", tmpWeight)) {
                                    return Access.CAN_SKIP;
                                } else if (!this.isCarLike() && way.hasTag("motorcar", "no")) {
                                    return Access.CAN_SKIP;
                                } else {
                                    if (!firstValue.isEmpty()) {
                                        if (this.restrictedValues.contains(firstValue) && !this.getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way)) {
                                            return Access.CAN_SKIP;
                                        }

                                        if (this.getConditionalTagInspector().isPermittedWayConditionallyRestricted(way)) {
                                            return Access.CAN_SKIP;
                                        }

                                        if (this.intendedValues.contains(firstValue)) {
                                            return Access.WAY;
                                        }
                                    }

                                    if (!this.isBlockFords() || !"ford".equals(highwayValue) && !way.hasTag("ford", new String[0])) {
                                        return this.getConditionalTagInspector().isPermittedWayConditionallyRestricted(way) ? Access.CAN_SKIP : Access.WAY;
                                    } else {
                                        return Access.CAN_SKIP;
                                    }
                                }
                            }
                        }
                    } else {
                        return Access.CAN_SKIP;
                    }
                }
            } else {
                return Access.CAN_SKIP;
            }
        }
    }

    protected double getSpeed(ReaderWay way) {
        return super.getSpeed(way);
    }

    protected double applyMaxSpeed(ReaderWay way, double speed) {
        double maxSpeed = this.getMaxSpeed(way);
        return maxSpeed >= 0.0D ? Math.max(5.0D, Math.min((double)this.maxPossibleSpeed, maxSpeed)) : speed;
    }

    public boolean smallerWeight(ReaderWay way, String key, double val) {
        String value = (String)way.getTag(key, "");
        if (OSMValueExtractor.isInvalidValue(value)) {
            return false;
        } else {
            try {
                double convVal = OSMValueExtractor.stringToTons(value);
                return convVal < val;
            } catch (Exception var8) {
                logger.warn("ID:" + way.getId() + " invalid weight value " + value);
                return false;
            }
        }
    }

    public boolean smaller(ReaderWay way, String key, double val) {
        String value = (String)way.getTag(key, "");
        if (OSMValueExtractor.isInvalidValue(value)) {
            return false;
        } else {
            try {
                double convVal = OSMValueExtractor.stringToMeter(value);
                return convVal < val;
            } catch (Exception var8) {
                logger.warn("ID:" + way.getId() + " invalid length, width or height value " + value);
                return false;
            }
        }
    }

    public int getVersion() {
        return 4;
    }

    public String toString() {
        return this.name;
    }
}

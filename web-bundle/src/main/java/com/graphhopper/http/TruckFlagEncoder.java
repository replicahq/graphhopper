package com.graphhopper.http;

import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.VehicleEncodedValues;

// adapted from prod GraphHopper code (not available in OSS GraphHopper)
public class TruckFlagEncoder {
    private static final int TRUCK_SPEED_BITS = 6;
    private static final int SMALL_TRUCK_SPEED_BITS = 7;
    private static final int TRUCK_SPEED_FACTOR = 2;  // truck and small_truck share this value
    private static final boolean ENABLE_TRUCK_TURN_RESTRICTIONS = false;

    // accept vehicleName as param rather than using TRUCK_VEHICLE_NAME to support custom speeds vehicles with nonstandard names
    public static VehicleEncodedValues createTruck(String vehicleName) {
        // turn costs is binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        int maxTurnCosts = ENABLE_TRUCK_TURN_RESTRICTIONS ? 1 : 0;
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(
                EncodingManager.getKey(vehicleName, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(
                EncodingManager.getKey(vehicleName, "average_speed"), TRUCK_SPEED_BITS, TRUCK_SPEED_FACTOR, true);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(vehicleName, maxTurnCosts) : null;
        return new VehicleEncodedValues(vehicleName, accessEnc, speedEnc, null, turnCostEnc);
    }

    // accept vehicleName as param rather than using SMALL_TRUCK_VEHICLE_NAME to support custom speeds vehicles with nonstandard names
    public static VehicleEncodedValues createSmallTruck(String vehicleName) {
        // turn costs is binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        int maxTurnCosts = ENABLE_TRUCK_TURN_RESTRICTIONS ? 1 : 0;
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(
                EncodingManager.getKey(vehicleName, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(
                EncodingManager.getKey(vehicleName, "average_speed"), SMALL_TRUCK_SPEED_BITS, TRUCK_SPEED_FACTOR, true);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(vehicleName, maxTurnCosts) : null;
        return new VehicleEncodedValues(vehicleName, accessEnc, speedEnc, null, turnCostEnc);
    }
}

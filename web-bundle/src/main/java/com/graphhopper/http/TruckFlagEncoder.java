package com.graphhopper.http;

import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.VehicleEncodedValues;

// adapted from prod GraphHopper code (not available in OSS GraphHopper)
public class TruckFlagEncoder {
    public static final String TRUCK_VEHICLE_NAME = "truck";
    public static final String SMALL_TRUCK_VEHICLE_NAME = "small_truck";
    private static final int TRUCK_SPEED_BITS = 6;
    private static final int SMALL_TRUCK_SPEED_BITS = 7;
    private static final int TRUCK_SPEED_FACTOR = 2;  // truck and small_truck share this value
    private static final boolean ENABLE_TRUCK_TURN_RESTRICTIONS = false;

    public static FlagEncoder createTruckFlagEncoder() {
        // turn costs is binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        int maxTurnCosts = ENABLE_TRUCK_TURN_RESTRICTIONS ? 1 : 0;
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(
                EncodingManager.getKey(TRUCK_VEHICLE_NAME, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(
                EncodingManager.getKey(TRUCK_VEHICLE_NAME, "average_speed"), TRUCK_SPEED_BITS, TRUCK_SPEED_FACTOR, false);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(TRUCK_VEHICLE_NAME, maxTurnCosts) : null;
        double maxSpeed = speedEnc.getNextStorableValue(TruckTagParser.EE_TRUCK_MAX_SPEED);

        // these flags are planned for GH removal
        boolean isHGV = true;
        boolean isMotorVehicle = true;

        return new VehicleEncodedValues(TRUCK_VEHICLE_NAME, accessEnc, speedEnc, null, null, turnCostEnc, maxSpeed, isMotorVehicle, isHGV);
    }

    public static FlagEncoder createSmallTruckFlagEncoder() {
        // turn costs is binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        int maxTurnCosts = ENABLE_TRUCK_TURN_RESTRICTIONS ? 1 : 0;
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(
                EncodingManager.getKey(SMALL_TRUCK_VEHICLE_NAME, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(
                EncodingManager.getKey(SMALL_TRUCK_VEHICLE_NAME, "average_speed"), SMALL_TRUCK_SPEED_BITS, TRUCK_SPEED_FACTOR, false);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(SMALL_TRUCK_VEHICLE_NAME, maxTurnCosts) : null;
        double maxSpeed = speedEnc.getNextStorableValue(TruckTagParser.EE_TRUCK_MAX_SPEED);

        // these flags are planned for GH removal
        boolean isHGV = false;
        boolean isMotorVehicle = true;

        return new VehicleEncodedValues(SMALL_TRUCK_VEHICLE_NAME, accessEnc, speedEnc, null, null, turnCostEnc, maxSpeed, isMotorVehicle, isHGV);
    }
}

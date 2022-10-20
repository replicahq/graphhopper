package com.graphhopper.http;

import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

import static com.graphhopper.routing.util.EncodingManager.getKey;

public class ReplicaFlagEncoderFactory extends DefaultFlagEncoderFactory {
    private static final String TRUCK_VEHICLE_NAME = "truck";
    private static final int TRUCK_SPEED_BITS = 6;
    private static final int TRUCK_SPEED_FACTOR = 2;
    private static final boolean ENABLE_TRUCK_TURN_RESTRICTIONS = false;

    @Override
    public FlagEncoder createFlagEncoder(final String name, PMap configuration) {
        // "configuration" is what you could attach to flag encoder names in the config file using undocumented dubious syntax.
        // (such as graph.flag_encoders: car|turn_costs=true)
        // Unless you have a high need for changing any of these values per-instance,
        // I recommend simply using this class as configuration instead.

        if (name.equals(TRUCK_VEHICLE_NAME)) {
            return createTruckFlagEncoder();
        } else if (name.startsWith("car_custom_speeds")) {
            PMap carCustomConfiguration = new PMap(configuration).putObject("name", name);
            return VehicleEncodedValues.car(carCustomConfiguration);
        }

        return super.createFlagEncoder(name, configuration);
    }

    // adapted from prod GraphHopper code (not available in OSS GraphHopper)
    private static FlagEncoder createTruckFlagEncoder() {
        int maxTurnCosts = ENABLE_TRUCK_TURN_RESTRICTIONS ? 1 : 0; // so far, if we use turn costs, it's a binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(getKey(TRUCK_VEHICLE_NAME, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(getKey(TRUCK_VEHICLE_NAME, "average_speed"), TRUCK_SPEED_BITS, TRUCK_SPEED_FACTOR, false);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(TRUCK_VEHICLE_NAME, maxTurnCosts) : null;
        double maxSpeed = speedEnc.getNextStorableValue(CarAndTruckTagParser.EE_TRUCK_MAX_SPEED);

        // Ignore isHGV and isMotorVehicle, those will be gone next version.
        boolean isHGV = true;
        boolean isMotorVehicle = true;

        return new VehicleEncodedValues(TRUCK_VEHICLE_NAME, accessEnc, speedEnc, null, null, turnCostEnc, maxSpeed, isMotorVehicle, isHGV);
    }

}
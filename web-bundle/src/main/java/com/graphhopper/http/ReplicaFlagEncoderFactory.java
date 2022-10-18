package com.graphhopper.http;

import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

import static com.graphhopper.routing.util.EncodingManager.getKey;

public class ReplicaFlagEncoderFactory extends DefaultFlagEncoderFactory {

    @Override
    public FlagEncoder createFlagEncoder(final String name, PMap configuration) {
        // "configuration" is what you could attach to flag encoder names in the config file using undocumented dubious syntax.
        // (such as graph.flag_encoders: car|turn_costs=true)
        // Unless you have a high need for changing any of these values per-instance,
        // I recommend simply using this class as configuration instead.

        // Ignore isHGV and isMotorVehicle, those will be gone next version.
        if (name.equals("car")) {
            return createVehicleFlagEncoder(name, 7, 2, CarAndTruckTagParser.EE_CAR_MAX_SPEED, false);
        } else if (name.equals("small_truck")) {
            return createVehicleFlagEncoder(name, 7, 2, CarAndTruckTagParser.EE_SMALL_TRUCK_MAX_SPEED, false);
        } else if (name.equals("truck")) {
            return createVehicleFlagEncoder(name, 6, 2, CarAndTruckTagParser.EE_TRUCK_MAX_SPEED, true);
        } else if (name.equals("van")) {
            return createVehicleFlagEncoder(name, 7, 2, CarAndTruckTagParser.EE_CAR_MAX_SPEED, false);
        } else if (name.startsWith("car_custom_speeds")) {
            return createVehicleFlagEncoder(name, 7, 2, CarAndTruckTagParser.EE_CAR_MAX_SPEED, false);
        }
        return super.createFlagEncoder(name, configuration);
    }

    private static FlagEncoder createVehicleFlagEncoder(String name, int speedBits, double speedFactor, double maxSpeed, boolean isHGV) {
        boolean turnRestrictions = false; // switch here to enable turn restrictions

        int maxTurnCosts = turnRestrictions ? 1 : 0; // so far, if we use turn costs, it's a binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(getKey(name, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(getKey(name, "average_speed"), speedBits, speedFactor, false);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(name, maxTurnCosts) : null;
        return new VehicleEncodedValues(name, accessEnc, speedEnc, null, null, turnCostEnc, speedEnc.getNextStorableValue(maxSpeed), true, isHGV);
    }

}
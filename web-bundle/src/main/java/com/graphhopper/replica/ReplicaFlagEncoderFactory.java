package com.graphhopper.replica;

import com.graphhopper.http.TruckTagParser;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

import java.util.Set;

import static com.graphhopper.routing.util.EncodingManager.getKey;

public class ReplicaFlagEncoderFactory extends DefaultFlagEncoderFactory {
    private static final String TRUCK_VEHICLE_NAME = "truck";
    private static final int TRUCK_SPEED_BITS = 6;
    private static final int TRUCK_SPEED_FACTOR = 2;
    private static final boolean ENABLE_TRUCK_TURN_RESTRICTIONS = false;
    private final Set<String> vehicleNamesWithCustomSpeeds;

    public ReplicaFlagEncoderFactory(Set<String> vehicleNamesWithCustomSpeeds) {
        this.vehicleNamesWithCustomSpeeds = vehicleNamesWithCustomSpeeds;
    }

    // "configuration" contains values from the flag encoder field in GraphHopper config (e.g.
    // graph.flag_encoders: car|turn_costs=true). we don't need to alter this field across different config files, so we
    // instead use this class to apply the necessary customizations to the default flag encoder
    @Override
    public FlagEncoder createFlagEncoder(final String name, PMap configuration) {
        if (vehicleNamesWithCustomSpeeds.contains(name)) {
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it. then we can delegate to the default car flag encoder
            PMap customSpeedsConfig = new PMap(configuration).putObject("name", name);
            return VehicleEncodedValues.car(customSpeedsConfig);
        } else if (name.equals(TRUCK_VEHICLE_NAME)) {
            return createTruckFlagEncoder();
        }

        return super.createFlagEncoder(name, configuration);
    }

    // adapted from prod GraphHopper code (not available in OSS GraphHopper)
    private static FlagEncoder createTruckFlagEncoder() {
        // turn costs is binary -- restricted or unrestricted (1 is scaled to infinity further down the code)
        int maxTurnCosts = ENABLE_TRUCK_TURN_RESTRICTIONS ? 1 : 0;
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(
                getKey(TRUCK_VEHICLE_NAME, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(
                getKey(TRUCK_VEHICLE_NAME, "average_speed"), TRUCK_SPEED_BITS, TRUCK_SPEED_FACTOR, false);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(TRUCK_VEHICLE_NAME, maxTurnCosts) : null;
        double maxSpeed = speedEnc.getNextStorableValue(TruckTagParser.EE_TRUCK_MAX_SPEED);

        // these flags are planned for GH removal
        boolean isHGV = true;
        boolean isMotorVehicle = true;

        return new VehicleEncodedValues(
                TRUCK_VEHICLE_NAME, accessEnc, speedEnc, null, null, turnCostEnc, maxSpeed, isMotorVehicle, isHGV);
    }

}

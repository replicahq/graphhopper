package com.graphhopper.replica;

import com.graphhopper.http.TruckFlagEncoder;
import com.graphhopper.http.TruckTagParser;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

import java.util.Set;

import static com.graphhopper.routing.util.EncodingManager.getKey;

public class ReplicaFlagEncoderFactory extends DefaultFlagEncoderFactory {
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
        } else if (name.equals(TruckFlagEncoder.TRUCK_VEHICLE_NAME)) {
            return TruckFlagEncoder.createTruckFlagEncoder();
        }

        return super.createFlagEncoder(name, configuration);
    }
}

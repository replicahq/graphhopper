package com.graphhopper.replica;

import com.graphhopper.http.TruckFlagEncoder;
import com.graphhopper.routing.util.DefaultVehicleEncodedValuesFactory;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

import java.util.Set;

public class ReplicaVehicleEncodedValuesFactory extends DefaultVehicleEncodedValuesFactory {
    private final Set<String> vehicleNamesWithCustomSpeeds;

    public ReplicaVehicleEncodedValuesFactory(Set<String> vehicleNamesWithCustomSpeeds) {
        this.vehicleNamesWithCustomSpeeds = vehicleNamesWithCustomSpeeds;
    }

    // "configuration" contains values from the flag encoder field in GraphHopper config (e.g.
    // graph.flag_encoders: car|turn_costs=true). we don't need to alter this field across different config files, so we
    // instead use this class to apply the necessary customizations to the default flag encoder
    @Override
    public VehicleEncodedValues createVehicleEncodedValues(final String name, PMap configuration) {
        if (vehicleNamesWithCustomSpeeds.contains(name)) {
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it. then we can delegate to the default car flag encoder
            PMap configWithName = new PMap(configuration).putObject("name", name);
            return VehicleEncodedValues.car(configWithName);
        } else if (name.equals(TruckFlagEncoder.TRUCK_VEHICLE_NAME)) {
            return TruckFlagEncoder.createTruck();
        } else if (name.equals(TruckFlagEncoder.SMALL_TRUCK_VEHICLE_NAME)) {
            return TruckFlagEncoder.createSmallTruck();
        }

        return super.createVehicleEncodedValues(name, configuration);
    }
}

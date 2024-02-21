package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.RouterConstants;
import com.graphhopper.customspeeds.CustomSpeedsVehicle;
import com.graphhopper.http.TruckFlagEncoder;
import com.graphhopper.routing.util.DefaultVehicleEncodedValuesFactory;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;

public class ReplicaVehicleEncodedValuesFactory extends DefaultVehicleEncodedValuesFactory {
    private final ImmutableMap<String, CustomSpeedsVehicle> customSpeedsVehiclesByName;

    public ReplicaVehicleEncodedValuesFactory(ImmutableMap<String, CustomSpeedsVehicle> customSpeedsVehiclesByName) {
        this.customSpeedsVehiclesByName = customSpeedsVehiclesByName;
    }

    // "configuration" contains values from the flag encoder field in GraphHopper config (e.g.
    // graph.flag_encoders: car|turn_costs=true). we don't need to alter this field across different config files, so we
    // instead use this class to apply the necessary customizations to the default flag encoder
    @Override
    public VehicleEncodedValues createVehicleEncodedValues(final String vehicleName, PMap configuration) {
        CustomSpeedsVehicle.VehicleType baseCustomSpeedsVehicleType = null;

        if (customSpeedsVehiclesByName.containsKey(vehicleName)) {
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it
            configuration.putObject("name", vehicleName);
            baseCustomSpeedsVehicleType = customSpeedsVehiclesByName.get(vehicleName).baseVehicleType;
        }

        // TODO: Do we need to have the first clause in these checks? Won't the super() call handle these cases fine?
        if (vehicleName.equals(RouterConstants.CAR_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.CAR) {
            return VehicleEncodedValues.car(configuration);
        } else if (vehicleName.equals(RouterConstants.TRUCK_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.TRUCK) {
            return TruckFlagEncoder.createTruck(vehicleName);
        } else if (vehicleName.equals(RouterConstants.SMALL_TRUCK_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.SMALL_TRUCK) {
            return TruckFlagEncoder.createSmallTruck(vehicleName);
        } else if (vehicleName.equals(RouterConstants.BIKE_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.BIKE) {
            return VehicleEncodedValues.bike(configuration);
        } else if (vehicleName.equals(RouterConstants.FOOT_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.FOOT) {
            return VehicleEncodedValues.foot(configuration);
        }

        return super.createVehicleEncodedValues(vehicleName, configuration);
    }
}

package com.graphhopper.replica;

import com.graphhopper.http.TruckFlagEncoder;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.util.PMap;

public class ReplicaFlagEncoderFactory extends DefaultFlagEncoderFactory {

    // "configuration" contains values from the flag encoder field in GraphHopper config (e.g.
    // graph.flag_encoders: car|turn_costs=true). we don't need to alter this field across different config files, so we
    // instead use this class to apply the necessary customizations to the default flag encoder
    @Override
    public FlagEncoder createFlagEncoder(final String name, PMap configuration) {
        if (name.equals(TruckFlagEncoder.TRUCK_VEHICLE_NAME)) {
            return TruckFlagEncoder.createTruckFlagEncoder();
        }

        return super.createFlagEncoder(name, configuration);
    }
}

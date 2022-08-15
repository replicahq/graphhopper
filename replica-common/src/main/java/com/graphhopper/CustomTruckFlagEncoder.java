package com.graphhopper;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ee.vehicles.TruckFlagEncoder;
import com.graphhopper.util.PMap;

public class CustomTruckFlagEncoder extends TruckFlagEncoder {

    public CustomTruckFlagEncoder(PMap properties, String profileName) {
        super(properties, profileName);
    }

    @Override
    protected double applyMaxSpeed(ReaderWay way, double speed) {
        double maxSpeed = getMaxSpeed(way);

        // We obey speed limits
        if (isValidSpeed(maxSpeed)) {
            // We assume that the average speed is 90% of the allowed maximum
            maxSpeed = maxSpeed * 0.9;
        }

        // Replica-specific change: return the min of the above 2 speeds, vs default behavior
        // of always returning maxSpeed when it's specified in OSM
        return Math.min(speed, maxSpeed);
    }
}

package com.replica;

import com.graphhopper.Trip;

import java.util.List;

/**
 * Extends Trip.WalkLeg to also store stable edge ids; these edge ids are automatically added to the proto response
 * Note that Trip.WalkLeg is also used for non-foot ACCESS and EGRESS leg modes; therefore, this custom class doesn't
 * assume walking as a mode, and stores mode as a field.
 */
public class CustomStreetLeg extends Trip.WalkLeg {
    public final List<String> stableEdgeIds;
    public final String mode;
    public final String travelSegmentType;

    public CustomStreetLeg(Trip.WalkLeg leg, List<String> stableEdgeIds, String travelSegmentType, String mode) {
        super(leg.departureLocation, leg.getDepartureTime(), leg.geometry,
                leg.distance, leg.instructions, leg.details, leg.getArrivalTime());
        this.stableEdgeIds = stableEdgeIds;
        this.travelSegmentType = travelSegmentType;
        this.mode = mode;
    }
}

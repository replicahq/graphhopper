package com.replica.router;

import com.graphhopper.Trip;

import java.util.List;

/**
 * Extends Trip.WalkLeg to also store stable edge ids; these edge ids are automatically added to the JSON response
 */
public class CustomWalkLeg extends Trip.WalkLeg {
    public final List<String> stableEdgeIds;
    public final String type;
    public final String travelSegmentType;

    public CustomWalkLeg(Trip.WalkLeg leg, List<String> stableEdgeIds, String travelSegmentType) {
        super(leg.departureLocation, leg.getDepartureTime(), leg.geometry,
                leg.distance, leg.instructions, leg.details, leg.getArrivalTime());
        this.stableEdgeIds = stableEdgeIds;
        this.details.clear();
        this.type = "foot";
        this.travelSegmentType = travelSegmentType;
    }
}

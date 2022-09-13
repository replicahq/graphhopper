package com.replica;

import com.graphhopper.Trip;

import java.util.List;

/**
 * New version of PtLeg class that stores stable edge IDs in class var;
 * this var will automatically get added to JSON response
 */
public class CustomPtLeg extends Trip.PtLeg {
    public final List<String> stableEdgeIds;
    public final String agencyName;
    public final String routeShortName;
    public final String routeLongName;
    public final String routeType;

    public CustomPtLeg(Trip.PtLeg leg, List<String> stableEdgeIds, List<Trip.Stop> updatedStops, double distance,
                       String agencyName, String routeShortName, String routeLongName, String routeType) {
        super(leg.feed_id, leg.isInSameVehicleAsPrevious, leg.trip_id, leg.route_id,
                leg.trip_headsign, updatedStops, distance, leg.travelTime, leg.geometry);
        this.stableEdgeIds = stableEdgeIds;
        this.agencyName = agencyName;
        this.routeShortName = routeShortName;
        this.routeLongName = routeLongName;
        this.routeType = routeType;
    }
}

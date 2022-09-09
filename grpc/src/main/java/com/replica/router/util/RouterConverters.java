package com.replica.router.util;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.Timestamp;
import com.graphhopper.Trip;
import com.graphhopper.util.DistanceCalcEarth;
import com.replica.router.CustomPtLeg;
import com.replica.router.CustomWalkLeg;
import com.replica.router.RouterImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toList;

public final class RouterConverters {

    private static final Logger logger = LoggerFactory.getLogger(RouterImpl.class);

    private RouterConverters() {
        // utility class
    }
    public static CustomPtLeg toCustomPtLeg(Trip.PtLeg leg, Map<String, String> gtfsFeedIdMapping, Map<String, String> gtfsLinkMappings, Map<String, List<String>> gtfsRouteInfo) {
        // Ordered list of GTFS route info, containing agency_name, route_short_name, route_long_name, route_type
        List<String> routeInfo = gtfsRouteInfo.getOrDefault(gtfsRouteInfoKey(leg), Lists.newArrayList("", "", "", ""));
        String routeType = routeInfo.get(3);

        List<Trip.Stop> stops = leg.stops;
        double legDistance = 0.0;
        List<String> stableEdgeIdSegments = Lists.newArrayList();
        for (int i = 0; i < stops.size() - 1; i++) {
            Trip.Stop from = stops.get(i);
            Trip.Stop to = stops.get(i + 1);
            legDistance += DistanceCalcEarth.DIST_EARTH.calcDist(
                    from.geometry.getY(), from.geometry.getX(), to.geometry.getY(), to.geometry.getX()
            );

            if (RouterConstants.STREET_BASED_ROUTE_TYPES.contains(Integer.parseInt(routeType))) {
                // Retrieve stable edge IDs for each stop->stop segment of leg
                String stopPair = gtfsFeedIdMapping.get(leg.feed_id) + ":" + from.stop_id + "," + to.stop_id;
                if (gtfsLinkMappings.containsKey(stopPair)) {
                    if (!gtfsLinkMappings.get(stopPair).isEmpty()) {
                        stableEdgeIdSegments.add(gtfsLinkMappings.get(stopPair));
                    }
                }
            }
        }
         List<String> stableEdgeIdsList = stableEdgeIdSegments.stream()
                    .flatMap(segment -> Arrays.stream(segment.split(",")))
                    .collect(toList());

        // Remove duplicates from stable ID list while retaining order;
        // needed because start/end of sequential segments overlap by 1 edge
        Set<String> stableEdgeIdsWithoutDuplicates = Sets.newLinkedHashSet(stableEdgeIdsList);
        stableEdgeIdsList.clear();
        stableEdgeIdsList.addAll(stableEdgeIdsWithoutDuplicates);

        // Convert any missing info to empty string to prevent NPE
        routeInfo = routeInfo.stream().map(info -> info == null ? "" : info).collect(toList());

        if (!gtfsRouteInfo.containsKey(gtfsRouteInfoKey(leg))) {
            logger.info("Failed to find route info for route " + leg.route_id + " for PT trip leg " + leg.toString());
        }

        // Add proper GTFS feed ID as prefix to all stop names in Leg
        List<Trip.Stop> updatedStops = Lists.newArrayList();
        for (Trip.Stop stop : leg.stops) {
            String updatedStopId = gtfsFeedIdMapping.get(leg.feed_id) + ":" + stop.stop_id;
            updatedStops.add(new Trip.Stop(updatedStopId, stop.stop_name, stop.geometry, stop.arrivalTime,
                    stop.plannedArrivalTime, stop.predictedArrivalTime, stop.arrivalCancelled, stop.departureTime,
                    stop.plannedDepartureTime, stop.predictedDepartureTime, stop.departureCancelled));
        }

        return new CustomPtLeg(leg, stableEdgeIdsList, updatedStops, legDistance,
                routeInfo.get(0), routeInfo.get(1), routeInfo.get(2), routeType);
    }

    public static PtLeg toPtLeg(Trip.Leg leg) {
        if (leg.type.equals("walk")) {
            CustomWalkLeg walkLeg = (CustomWalkLeg) leg;
            return PtLeg.newBuilder()
                    .setDepartureTime(Timestamp.newBuilder()
                            .setSeconds(walkLeg.getDepartureTime().getTime() / 1000) // getTime() returns millis
                            .build())
                    .setArrivalTime(Timestamp.newBuilder()
                            .setSeconds(walkLeg.getArrivalTime().getTime() / 1000) // getTime() returns millis
                            .build())
                    .setDistanceMeters(walkLeg.getDistance())
                    .addAllStableEdgeIds(walkLeg.stableEdgeIds)
                    .setTravelSegmentType(walkLeg.travelSegmentType)
                    .build();
        } else { // leg is a PT leg
            CustomPtLeg ptLeg = (CustomPtLeg) leg;
            TransitMetadata ptMetadata = TransitMetadata.newBuilder()
                    .setTripId(ptLeg.trip_id)
                    .setRouteId(ptLeg.route_id)
                    .setAgencyName(ptLeg.agencyName)
                    .setRouteShortName(ptLeg.routeShortName)
                    .setRouteLongName(ptLeg.routeLongName)
                    .setRouteType(ptLeg.routeType)
                    .setDirection(ptLeg.trip_headsign)
                    .addAllStops(ptLeg.stops.stream().map(stop -> Stop.newBuilder()
                            .setStopId(stop.stop_id)
                            .setStopName(stop.stop_name)
                            .setArrivalTime(stop.arrivalTime == null ? Timestamp.newBuilder().build()
                                    : Timestamp.newBuilder().setSeconds(stop.arrivalTime.getTime() / 1000).build())
                            .setDepartureTime(stop.departureTime == null ? Timestamp.newBuilder().build()
                                    : Timestamp.newBuilder().setSeconds(stop.departureTime.getTime() / 1000).build())
                            .setPoint(Point.newBuilder().setLat(stop.geometry.getY()).setLon(stop.geometry.getX()).build())
                            .build()).collect(toList())
                    ).build();
            return PtLeg.newBuilder()
                    .setDepartureTime(Timestamp.newBuilder()
                            .setSeconds(ptLeg.getDepartureTime().getTime() / 1000) // getTime() returns millis
                            .build())
                    .setArrivalTime(Timestamp.newBuilder()
                            .setSeconds(ptLeg.getArrivalTime().getTime() / 1000) // getTime() returns millis
                            .build())
                    .setDistanceMeters(ptLeg.getDistance())
                    .addAllStableEdgeIds(ptLeg.stableEdgeIds)
                    .setTransitMetadata(ptMetadata)
                    .build();
        }
    }

    public static CustomWalkLeg toCustomWalkLeg(Trip.WalkLeg leg, String travelSegmentType) {
        return new CustomWalkLeg(leg, fetchWalkLegStableIds(leg), travelSegmentType);
    }

    private static List<String> fetchWalkLegStableIds(Trip.WalkLeg leg) {
        return leg.details.get("stable_edge_ids").stream()
                .map(idPathDetail -> (String) idPathDetail.getValue())
                .filter(id -> id.length() == 20)
                .collect(toList());
    }

    private static String gtfsRouteInfoKey(Trip.PtLeg leg) {
        return leg.feed_id + ":" + leg.route_id;
    }
}

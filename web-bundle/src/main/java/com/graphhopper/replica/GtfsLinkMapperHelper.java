package com.graphhopper.replica;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GtfsLinkMapperHelper {
    private static final Logger logger = LoggerFactory.getLogger(GtfsLinkMapperHelper.class);

    // Define GTFS route types we care about linking to street edges: tram, bus, and cable car
    // Taken from Google's GTFS spec: https://developers.google.com/transit/gtfs/reference#routestxt
    public static final Set<Integer> STREET_BASED_ROUTE_TYPES = Sets.newHashSet(0, 3, 5);

    public static SetMultimap<String, Pair<Stop, Stop>> extractStopPairsFromFeed(GTFSFeed feed) {
        logger.info("Processing GTFS feed " + feed.feedId);

        // For mapping purposes, only look at routes for transit that use the street network
        Set<String> streetBasedRouteIdsForFeed = feed.routes.values().stream()
                .filter(route -> STREET_BASED_ROUTE_TYPES.contains(route.route_type))
                .map(route -> route.route_id)
                .collect(Collectors.toSet());

        // Find all GTFS trips for each route
        Set<String> tripsForStreetBasedRoutes = feed.trips.values().stream()
                .filter(trip -> streetBasedRouteIdsForFeed.contains(trip.route_id))
                .map(trip -> trip.trip_id)
                .collect(Collectors.toSet());

        // Find all stops for each trip
        SetMultimap<String, StopTime> tripIdToStopsInTrip = HashMultimap.create();
        feed.stop_times.values().stream()
                .filter(stopTime -> tripsForStreetBasedRoutes.contains(stopTime.trip_id))
                .forEach(stopTime -> tripIdToStopsInTrip.put(stopTime.trip_id, stopTime));

        Set<String> stopIdsForStreetBasedTrips = tripIdToStopsInTrip.values().stream()
                .map(stopTime -> stopTime.stop_id)
                .collect(Collectors.toSet());

        Map<String, Stop> stopsForStreetBasedTrips = feed.stops.values().stream()
                .filter(stop -> stopIdsForStreetBasedTrips.contains(stop.stop_id))
                .collect(Collectors.toMap(stop -> stop.stop_id, stop -> stop));

        SetMultimap<String, Pair<Stop, Stop>> routeIdToStopPairs = HashMultimap.create();
        tripIdToStopsInTrip.keySet().stream()
                .forEach(tripId -> {
                    getODStopsForTrip(tripIdToStopsInTrip.get(tripId), stopsForStreetBasedTrips).stream()
                            .forEach(stopPair -> {
                                routeIdToStopPairs.put(feed.trips.get(tripId).route_id, stopPair);
                            });
                });

        logger.info("There are " + streetBasedRouteIdsForFeed.size() + " GTFS routes containing "
                + tripsForStreetBasedRoutes.size() + " total trips to process for this feed. Routes to be computed for "
                + Sets.newHashSet(routeIdToStopPairs.values()).size() + " unique stop->stop pairs");
        return routeIdToStopPairs;
    }

    // Given a set of StopTimes for a trip, and an overall mapping of stop IDs->Stop,
    // return a set of sequentially-ordered stop->stop pairs that make up the trip
    private static List<Pair<Stop, Stop>> getODStopsForTrip(Set<StopTime> stopsInTrip, Map<String, Stop> allStops) {
        StopTime[] sortedStopsArray = new StopTime[stopsInTrip.size()];
        Arrays.sort(stopsInTrip.toArray(sortedStopsArray), (a, b) -> a.stop_sequence < b.stop_sequence ? -1 : 1);

        List<Pair<Stop, Stop>> odStopsForTrip = Lists.newArrayList();
        for (int i = 0; i < sortedStopsArray.length - 1; i++) {
            Stop startStop = allStops.get(sortedStopsArray[i].stop_id);
            Stop endStop = allStops.get(sortedStopsArray[i + 1].stop_id);
            // Filter out stop-stop pairs where the stops are identical
            if (startStop.stop_id.equals(endStop.stop_id)) {
                continue;
            };
            odStopsForTrip.add(Pair.of(startStop, endStop));
        }
        return odStopsForTrip;
    }
}

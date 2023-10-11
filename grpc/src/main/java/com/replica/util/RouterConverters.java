package com.replica.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.Timestamp;
import com.graphhopper.GHRequest;
import com.graphhopper.ResponsePath;
import com.graphhopper.Trip;
import com.graphhopper.gtfs.Request;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.GHPoint;
import com.replica.CustomPtLeg;
import com.replica.CustomStreetLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.graphhopper.util.Parameters.Routing.INSTRUCTIONS;
import static java.util.stream.Collectors.toList;

public final class RouterConverters {

    private static final Logger logger = LoggerFactory.getLogger(RouterConverters.class);
    private static final ObjectMapper jsonOM = Jackson.newObjectMapper();

    private static final int DEFAULT_MAX_VISITED_NODES = 1_000_000;

    private RouterConverters() {
        // utility class
    }

    public static CustomPtLeg toCustomPtLeg(Trip.PtLeg leg,
                                            Map<String, String> gtfsFeedIdMapping,
                                            Map<String, String> gtfsLinkMappings,
                                            Map<String, List<String>> gtfsRouteInfo) {
        // Ordered list of GTFS route info, containing agency_name, route_short_name, route_long_name, route_type
        List<String> routeInfo =
                gtfsRouteInfo.getOrDefault(gtfsRouteInfoKey(leg), Lists.newArrayList("", "", "", ""));
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
            updatedStops.add(new Trip.Stop(updatedStopId, stop.stop_sequence, stop.stop_name, stop.geometry, stop.arrivalTime,
                    stop.plannedArrivalTime, stop.predictedArrivalTime, stop.arrivalCancelled, stop.departureTime,
                    stop.plannedDepartureTime, stop.predictedDepartureTime, stop.departureCancelled));
        }

        return new CustomPtLeg(leg, stableEdgeIdsList, updatedStops, legDistance,
                routeInfo.get(0), routeInfo.get(1), routeInfo.get(2), routeType);
    }

    public static PtLeg toPtLeg(Trip.Leg leg) {
        if (!leg.type.equals("pt")) {
            CustomStreetLeg streetLeg = (CustomStreetLeg) leg;
            return PtLeg.newBuilder()
                    .setDepartureTime(Timestamp.newBuilder()
                            .setSeconds(streetLeg.getDepartureTime().getTime() / 1000) // getTime() returns millis
                            .build())
                    .setArrivalTime(Timestamp.newBuilder()
                            .setSeconds(streetLeg.getArrivalTime().getTime() / 1000) // getTime() returns millis
                            .build())
                    .setDistanceMeters(streetLeg.getDistance())
                    .addAllStableEdgeIds(streetLeg.stableEdgeIds)
                    .setTravelSegmentType(streetLeg.travelSegmentType)
                    .setMode(streetLeg.mode)
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
                    .setDirection(ptLeg.trip_headsign == null ? "" : ptLeg.trip_headsign)
                    .addAllStops(ptLeg.stops.stream().map(stop -> Stop.newBuilder()
                            .setStopId(stop.stop_id)
                            .setStopName(stop.stop_name)
                            .setArrivalTime(stop.arrivalTime == null ? Timestamp.newBuilder().build()
                                    : Timestamp.newBuilder().setSeconds(stop.arrivalTime.getTime() / 1000).build())
                            .setDepartureTime(stop.departureTime == null ? Timestamp.newBuilder().build()
                                    : Timestamp.newBuilder().setSeconds(stop.departureTime.getTime() / 1000).build())
                            .setPoint(Point.newBuilder()
                                    .setLat(stop.geometry.getY())
                                    .setLon(stop.geometry.getX())
                                    .build())
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

    public static GHRequest toGHRequest(StreetRouteRequest request) {
        GHRequest ghRequest = new GHRequest(
                request.getPointsList().stream()
                        .map(p -> new GHPoint(p.getLat(), p.getLon()))
                        .collect(Collectors.toList()));
        ghRequest.setProfile(request.getProfile());
        ghRequest.setLocale(Locale.US);
        ghRequest.setPathDetails(Lists.newArrayList("stable_edge_ids", "time"));

        PMap hints = new PMap();
        hints.putObject(INSTRUCTIONS, false);
        if (request.getAlternateRouteMaxPaths() > 1) {
            ghRequest.setAlgorithm("alternative_route");
            hints.putObject("alternative_route.max_paths", request.getAlternateRouteMaxPaths());
            hints.putObject("alternative_route.max_weight_factor", request.getAlternateRouteMaxWeightFactor());
            hints.putObject("alternative_route.max_share_factor", request.getAlternateRouteMaxShareFactor());
        }
        ghRequest.getHints().putAll(hints);
        return ghRequest;
    }

    public static GHRequest toGHRequest(CustomRouteRequest request) {
        GHRequest ghRequest = new GHRequest(
                request.getPointsList().stream()
                        .map(p -> new GHPoint(p.getLat(), p.getLon()))
                        .collect(Collectors.toList()));
        ghRequest.setProfile(request.getProfile());
        ghRequest.setLocale(Locale.US);
        ghRequest.setPathDetails(Lists.newArrayList("stable_edge_ids", "time"));

        PMap hints = new PMap();
        CustomModel customModel;
        try {
            customModel = jsonOM.readValue(request.getCustomModel(), CustomModel.class);
            ghRequest.setCustomModel(customModel);
        } catch (Exception e) {
            logger.error(e.getMessage());
            logger.error(e.getStackTrace().toString());
            throw new RuntimeException(
                    "Couldn't read custom model from GH request! Full request: " + request.toString());
        }
        hints.putObject(Parameters.CH.DISABLE, true);

        hints.putObject(INSTRUCTIONS, false);
        if (request.getAlternateRouteMaxPaths() > 1) {
            ghRequest.setAlgorithm("alternative_route");
            hints.putObject("alternative_route.max_paths", request.getAlternateRouteMaxPaths());
            hints.putObject("alternative_route.max_weight_factor", request.getAlternateRouteMaxWeightFactor());
            hints.putObject("alternative_route.max_share_factor", request.getAlternateRouteMaxShareFactor());
        }
        ghRequest.getHints().putAll(hints);
        return ghRequest;
    }

    public static Request toGHPtRequest(PtRouteRequest request) {
        Point fromPoint = request.getPoints(0);
        Point toPoint = request.getPoints(1);

        Request ghPtRequest = new Request(fromPoint.getLat(), fromPoint.getLon(), toPoint.getLat(), toPoint.getLon());
        ghPtRequest.setEarliestDepartureTime(Instant.ofEpochSecond(
                request.getEarliestDepartureTime().getSeconds(), request.getEarliestDepartureTime().getNanos()
        ));
        ghPtRequest.setLimitSolutions(request.getLimitSolutions());
        ghPtRequest.setLocale(Locale.US);
        ghPtRequest.setArriveBy(false);
        ghPtRequest.setPathDetails(Lists.newArrayList("stable_edge_ids"));
        ghPtRequest.setProfileQuery(true);
        ghPtRequest.setMaxProfileDuration(Duration.ofMinutes(request.getMaxProfileDuration()));
        ghPtRequest.setLimitStreetTime(Duration.ofSeconds(request.getLimitStreetTimeSeconds()));
        ghPtRequest.setIgnoreTransfers(!request.getUsePareto()); // ignoreTransfers=true means pareto queries are off
        ghPtRequest.setBetaTransfers(request.getBetaTransfers());
        ghPtRequest.setMaxVisitedNodes(request.getMaxVisitedNodes() == 0 ? DEFAULT_MAX_VISITED_NODES : request.getMaxVisitedNodes());

        // Set access and egress leg modes if they've been explicitly provided.
        // Note: even if modes other than walk are requested, Graphhopper will return these legs
        // as Trip.WalkLeg objects
        // Note: GraphHopper currently only accepts profiles with standard "base" names for
        // access/egress modes. Therefore, "mode" and "profile" are somewhat interchangeable here
        String accessMode = request.getAccessMode().equals("") ? "foot" : request.getAccessMode();
        String egressMode = request.getEgressMode().equals("") ? "foot" : request.getEgressMode();
        ghPtRequest.setAccessProfile(accessMode);
        ghPtRequest.setEgressProfile(egressMode);

        // If beta access/egress time hasn't been explicitly set, assume caller is using older protos
        // where only one global beta walk time param is provided, and use that value
        double betaAccessTime = request.getBetaAccessTime() == 0.0 ? request.getBetaWalkTime() : request.getBetaAccessTime();
        double betaEgressTime = request.getBetaEgressTime() == 0.0 ? request.getBetaWalkTime() : request.getBetaEgressTime();
        ghPtRequest.setBetaAccessTime(betaAccessTime);
        ghPtRequest.setBetaEgressTime(betaEgressTime);

        return ghPtRequest;
    }

    public static StreetPath toStreetPath(ResponsePath responsePath, String profile) {
        List<String> pathStableEdgeIds = responsePath.getPathDetails().get("stable_edge_ids").stream()
                .map(pathDetail -> (String) pathDetail.getValue())
                .collect(Collectors.toList());

        List<Long> edgeTimes = responsePath.getPathDetails().get("time").stream()
                .map(pathDetail -> (Long) pathDetail.getValue())
                .collect(Collectors.toList());

        return StreetPath.newBuilder()
                .setDurationMillis(responsePath.getTime())
                .setDistanceMeters(responsePath.getDistance())
                .addAllStableEdgeIds(pathStableEdgeIds)
                .addAllEdgeDurationsMillis(edgeTimes)
                .setPoints(responsePath.getPoints().toLineString(false).toString())
                .setProfile(profile)
                .build();
    }

    public static PtPath toPtPath(ResponsePath responsePath) {
        return PtPath.newBuilder()
                .setDurationMillis(responsePath.getTime())
                .setDistanceMeters(responsePath.getDistance())
                .setTransfers(responsePath.getNumChanges())
                .addAllLegs(responsePath.getLegs().stream()
                        .map(RouterConverters::toPtLeg)
                        .collect(toList()))
                .build();
    }

    public static CustomStreetLeg toCustomStreetLeg(Trip.WalkLeg leg, String travelSegmentType, String mode) {
        return new CustomStreetLeg(leg, fetchStreetLegStableIds(leg), travelSegmentType, mode);
    }

    private static List<String> fetchStreetLegStableIds(Trip.WalkLeg leg) {
        return leg.details.get("stable_edge_ids").stream()
                .map(idPathDetail -> (String) idPathDetail.getValue())
                .collect(toList());
    }

    private static String gtfsRouteInfoKey(Trip.PtLeg leg) {
        return leg.feed_id + ":" + leg.route_id;
    }
}

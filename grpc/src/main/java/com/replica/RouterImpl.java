/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.replica;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.*;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.routing.*;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.PMap;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.graphhopper.util.Parameters.Routing.INSTRUCTIONS;
import static java.util.stream.Collectors.toList;

public class RouterImpl extends router.RouterGrpc.RouterImplBase {

    final Set<Integer> STREET_BASED_ROUTE_TYPES = Sets.newHashSet(0, 3, 5);

    private static final Logger logger = LoggerFactory.getLogger(RouterImpl.class);
    private final GraphHopper graphHopper;
    private final PtRouter ptRouter;
    private Map<String, String> gtfsLinkMappings;
    private Map<String, List<String>> gtfsRouteInfo;
    private Map<String, String> gtfsFeedIdMapping;
    private final StatsDClient statsDClient;
    private String regionName;

    public RouterImpl(GraphHopper graphHopper, PtRouter ptRouter,
                      Map<String, String> gtfsLinkMappings,
                      Map<String, List<String>> gtfsRouteInfo,
                      Map<String, String> gtfsFeedIdMapping,
                      StatsDClient statsDClient,
                      String regionName) {
        this.graphHopper = graphHopper;
        this.ptRouter = ptRouter;
        this.gtfsLinkMappings = gtfsLinkMappings;
        this.gtfsRouteInfo = gtfsRouteInfo;
        this.gtfsFeedIdMapping = gtfsFeedIdMapping;
        this.statsDClient = statsDClient;
        this.regionName = regionName;
    }

    @Override
    public void routeStreetMode(StreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        GHRequest ghRequest = new GHRequest(
                request.getPointsList().stream().map(p -> new GHPoint(p.getLat(), p.getLon())).collect(Collectors.toList())
        );
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

        try {
            GHResponse ghResponse = graphHopper.route(ghRequest);
            if (ghResponse.getAll().size() == 0) {
                String message = "Path could not be found between "
                        + ghRequest.getPoints().get(0).lat + "," + ghRequest.getPoints().get(0).lon + " to "
                        + ghRequest.getPoints().get(1).lat + "," + ghRequest.getPoints().get(1).lon;
                // logger.warn(message);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:false"};
                tags = applyRegionName(tags, regionName);
                sendDatadogStats(statsDClient, tags, durationSeconds);

                Status status = Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            } else {
                StreetRouteReply.Builder replyBuilder = StreetRouteReply.newBuilder();
                for (ResponsePath responsePath : ghResponse.getAll()) {
                    List<String> pathStableEdgeIds = responsePath.getPathDetails().get("stable_edge_ids").stream()
                            .map(pathDetail -> (String) pathDetail.getValue())
                            .collect(Collectors.toList());

                    List<Long> edgeTimes = responsePath.getPathDetails().get("time").stream()
                            .map(pathDetail -> (Long) pathDetail.getValue())
                            .collect(Collectors.toList());

                    replyBuilder.addPaths(StreetPath.newBuilder()
                            .setDurationMillis(responsePath.getTime())
                            .setDistanceMeters(responsePath.getDistance())
                            .addAllStableEdgeIds(pathStableEdgeIds)
                            .addAllEdgeDurationsMillis(edgeTimes)
                    );
                }

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
                tags = applyRegionName(tags, regionName);
                sendDatadogStats(statsDClient, tags, durationSeconds);

                responseObserver.onNext(replyBuilder.build());
                responseObserver.onCompleted();
            }
        } catch (Exception e) {
            String message = "GH internal error! Path could not be found between "
                    + ghRequest.getPoints().get(0).lat + "," + ghRequest.getPoints().get(0).lon + " to "
                    + ghRequest.getPoints().get(1).lat + "," + ghRequest.getPoints().get(1).lon;
            logger.error(message, e);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:error"};
            tags = applyRegionName(tags, regionName);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    @Override
    public void info(InfoRequest request, StreamObserver<InfoReply> responseObserver) {
        GraphHopperStorage storage = graphHopper.getGraphHopperStorage();
        responseObserver.onNext(InfoReply.newBuilder().addAllBbox(Arrays.asList(storage.getBounds().minLon, storage.getBounds().minLat, storage.getBounds().maxLon, storage.getBounds().maxLat)).build());
        responseObserver.onCompleted();
    }

    @Override
    public void routePt(PtRouteRequest request, StreamObserver<PtRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

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
        ghPtRequest.setBetaStreetTime(request.getBetaWalkTime());
        ghPtRequest.setLimitStreetTime(Duration.ofSeconds(request.getLimitStreetTimeSeconds()));
        ghPtRequest.setIgnoreTransfers(!request.getUsePareto()); // ignoreTransfers=true means pareto queries are off
        ghPtRequest.setBetaTransfers(request.getBetaTransfers());

        try {
            GHResponse ghResponse = ptRouter.route(ghPtRequest);
            List<ResponsePath> pathsWithStableIds = Lists.newArrayList();
            for (ResponsePath path : ghResponse.getAll()) {
                // Ignore walking-only responses, because we route those separately from PT
                if (path.getLegs().size() == 1 && path.getLegs().get(0).type.equals("walk")) {
                    continue;
                }

                // Add stable edge IDs to PT legs
                List<Trip.Leg> ptLegs = path.getLegs().stream()
                        .filter(leg -> leg.type.equals("pt"))
                        .map(leg -> getCustomPtLeg((Trip.PtLeg)leg))
                        .collect(toList());

                // Add stable edge IDs to walk legs
                List<Trip.Leg> walkLegs = path.getLegs().stream()
                        .filter(leg -> leg.type.equals("walk"))
                        .collect(toList());

                Trip.WalkLeg firstLeg = (Trip.WalkLeg) walkLegs.get(0);
                Trip.WalkLeg lastLeg = (Trip.WalkLeg) walkLegs.get(1);

                List<String> lastLegStableIds = lastLeg.details.get("stable_edge_ids").stream()
                        .map(idPathDetail -> (String) idPathDetail.getValue())
                        .filter(id -> id.length() == 20)
                        .collect(toList());

                // The first leg contains stable IDs for both walking legs for some reason,
                // so we remove the IDs from the last leg
                List<String> firstLegStableIds = firstLeg.details.get("stable_edge_ids").stream()
                        .map(idPathDetail -> (String) idPathDetail.getValue())
                        .filter(id -> id.length() == 20)
                        .collect(toList());
                firstLegStableIds.removeAll(lastLegStableIds);

                // Replace the path's legs with newly-constructed legs containing stable edge IDs
                path.getLegs().clear();
                path.getLegs().add(new CustomWalkLeg(firstLeg, firstLegStableIds, "ACCESS"));
                path.getLegs().addAll(ptLegs);
                path.getLegs().add(new CustomWalkLeg(lastLeg, lastLegStableIds, "EGRESS"));
                path.getPathDetails().clear();
                pathsWithStableIds.add(path);
            }

            if (pathsWithStableIds.size() == 0) {
                String message = "Transit path could not be found between " + fromPoint.getLat() + "," +
                        fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon();
                // logger.warn(message);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:pt", "api:grpc", "routes_found:false"};
                tags = applyRegionName(tags, regionName);
                sendDatadogStats(statsDClient, tags, durationSeconds);

                Status status = Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            } else {
                PtRouteReply.Builder replyBuilder = PtRouteReply.newBuilder();
                for (ResponsePath responsePath : pathsWithStableIds) {
                    List<FootLeg> footLegs = responsePath.getLegs().stream()
                            .filter(leg -> leg.type.equals("walk"))
                            .map(leg -> (CustomWalkLeg) leg)
                            .map(leg -> FootLeg.newBuilder()
                                    .setDepartureTime(Timestamp.newBuilder()
                                            .setSeconds(leg.getDepartureTime().getTime() / 1000) // getTime() returns millis
                                            .build())
                                    .setArrivalTime(Timestamp.newBuilder()
                                            .setSeconds(leg.getArrivalTime().getTime() / 1000) // getTime() returns millis
                                            .build())
                                    .setDistanceMeters(leg.getDistance())
                                    .addAllStableEdgeIds(leg.stableEdgeIds)
                                    .setTravelSegmentType(leg.travelSegmentType)
                                    .build())
                            .collect(toList());

                    List<PtLeg> ptLegs = responsePath.getLegs().stream()
                            .filter(leg -> leg.type.equals("pt"))
                            .map(leg -> (CustomPtLeg) leg)
                            .map(leg -> PtLeg.newBuilder()
                                    .setDepartureTime(Timestamp.newBuilder()
                                            .setSeconds(leg.getDepartureTime().getTime() / 1000) // getTime() returns millis
                                            .build())
                                    .setArrivalTime(Timestamp.newBuilder()
                                            .setSeconds(leg.getArrivalTime().getTime() / 1000) // getTime() returns millis
                                            .build())
                                    .setDistanceMeters(leg.getDistance())
                                    .addAllStableEdgeIds(leg.stableEdgeIds)
                                    .setTripId(leg.trip_id)
                                    .setRouteId(leg.route_id)
                                    .setAgencyName(leg.agencyName)
                                    .setRouteShortName(leg.routeShortName != null ? leg.routeShortName : "")
                                    .setRouteLongName(leg.routeLongName != null ? leg.routeLongName : "")
                                    .setRouteType(leg.routeType)
                                    .setDirection(leg.trip_headsign)
                                    .addAllStops(leg.stops.stream().map(stop -> Stop.newBuilder()
                                            .setStopId(stop.stop_id)
                                            .setStopName(stop.stop_name)
                                            .setArrivalTime(stop.arrivalTime == null ? Timestamp.newBuilder().build()
                                                    : Timestamp.newBuilder().setSeconds(stop.arrivalTime.getTime() / 1000).build())
                                            .setDepartureTime(stop.departureTime == null ? Timestamp.newBuilder().build()
                                                    : Timestamp.newBuilder().setSeconds(stop.departureTime.getTime() / 1000).build())
                                            .setPoint(Point.newBuilder().setLat(stop.geometry.getY()).setLon(stop.geometry.getX()).build())
                                            .build()).collect(toList())
                                    ).build()
                            ).collect(toList());

                    replyBuilder.addPaths(PtPath.newBuilder()
                            .setDurationMillis(responsePath.getTime())
                            .setDistanceMeters(responsePath.getDistance())
                            .setTransfers(responsePath.getNumChanges())
                            .addAllFootLegs(footLegs)
                            .addAllPtLegs(ptLegs)
                    );
                }

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:pt", "api:grpc", "routes_found:true"};
                tags = applyRegionName(tags, regionName);
                sendDatadogStats(statsDClient, tags, durationSeconds);

                responseObserver.onNext(replyBuilder.build());
                responseObserver.onCompleted();
            }
        } catch (PointNotFoundException e) {
            String message = "Path could not be found between " + fromPoint.getLat() + "," +
                    fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon() +
                    "; one or both endpoints could not be snapped to a road segment";
            // logger.warn(message);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:pt", "api:grpc", "routes_found:false"};
            tags = applyRegionName(tags, regionName);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.NOT_FOUND.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        } catch (Exception e) {
            logger.error("GraphHopper internal error! ", e);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:pt", "api:grpc", "routes_found:error"};
            tags = applyRegionName(tags, regionName);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage("GH internal error! Path could not be found between " + fromPoint.getLat() + "," +
                            fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon())
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    public static class CustomWalkLeg extends Trip.WalkLeg {
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

    // Create new version of PtLeg class that stores stable edge IDs in class var;
    // this var will automatically get added to JSON response
    public static class CustomPtLeg extends Trip.PtLeg {
        public final List<String> stableEdgeIds;
        public final String agencyName;
        public final String routeShortName;
        public final String routeLongName;
        public final String routeType;

        public CustomPtLeg(Trip.PtLeg leg, List<String> stableEdgeIds, List<Trip.Stop> updatedStops,
                           String agencyName, String routeShortName, String routeLongName, String routeType) {
            super(leg.feed_id, leg.isInSameVehicleAsPrevious, leg.trip_id, leg.route_id,
                    leg.trip_headsign, updatedStops, leg.distance, leg.travelTime, leg.geometry);
            this.stableEdgeIds = stableEdgeIds;
            this.agencyName = agencyName;
            this.routeShortName = routeShortName;
            this.routeLongName = routeLongName;
            this.routeType = routeType;
        }
    }

    private CustomPtLeg getCustomPtLeg(Trip.PtLeg leg) {
        // Ordered list of GTFS route info, containing agency_name, route_short_name, route_long_name, route_type
        List<String> routeInfo = gtfsRouteInfo.getOrDefault(gtfsRouteInfoKey(leg), Lists.newArrayList("", "", "", ""));
        String routeType = routeInfo.get(3);

        List<String> stableEdgeIdsList = Lists.newArrayList();
        if (STREET_BASED_ROUTE_TYPES.contains(Integer.parseInt(routeType))) {
            // Retrieve stable edge IDs for each stop->stop segment of leg
            List<Trip.Stop> stops = leg.stops;
            List<String> stableEdgeIdSegments = Lists.newArrayList();
            for (int i = 0; i < stops.size() - 1; i++) {
                String stopPair = gtfsFeedIdMapping.get(leg.feed_id) + ":" + stops.get(i).stop_id + "," + stops.get(i + 1).stop_id;
                if (gtfsLinkMappings.containsKey(stopPair)) {
                    if (!gtfsLinkMappings.get(stopPair).isEmpty()) {
                        stableEdgeIdSegments.add(gtfsLinkMappings.get(stopPair));
                    }
                }
            }
            stableEdgeIdsList = stableEdgeIdSegments.stream()
                    .flatMap(segment -> Arrays.stream(segment.split(",")))
                    .collect(toList());

            // Remove duplicates from stable ID list while retaining order;
            // needed because start/end of sequential segments overlap by 1 edge
            Set<String> stableEdgeIdsWithoutDuplicates = Sets.newLinkedHashSet(stableEdgeIdsList);
            stableEdgeIdsList.clear();
            stableEdgeIdsList.addAll(stableEdgeIdsWithoutDuplicates);
        }

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

        return new CustomPtLeg(leg, stableEdgeIdsList, updatedStops,
                routeInfo.get(0), routeInfo.get(1), routeInfo.get(2), routeType);
    }

    private static String gtfsRouteInfoKey(Trip.PtLeg leg) {
        return leg.feed_id + ":" + leg.route_id;
    }

    private static void sendDatadogStats(StatsDClient statsDClient, String[] tags, double durationSeconds) {
        if (statsDClient != null) {
            statsDClient.incrementCounter("routers.num_requests", tags);
            statsDClient.distribution("routers.request_seconds", durationSeconds, tags);
        }
    }

    // If a region name has been set, add it to tag list
    private static String[] applyRegionName(String[] tags, String regionName) {
        if (regionName == null) {
            return tags;
        } else {
            List<String> newTags = Lists.newArrayList(tags);
            newTags.add("replica_region:" + regionName);
            return newTags.toArray(new String[newTags.size()]);
        }
    }
}

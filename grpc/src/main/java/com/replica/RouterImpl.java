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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.*;
import com.graphhopper.config.Profile;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.routing.*;
import com.graphhopper.routing.util.CustomModel;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
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
import static java.util.stream.Collectors.*;

public class RouterImpl extends router.RouterGrpc.RouterImplBase {

    final Set<Integer> STREET_BASED_ROUTE_TYPES = Sets.newHashSet(0, 3, 5);

    private static final Logger logger = LoggerFactory.getLogger(RouterImpl.class);
    private final GraphHopper graphHopper;
    private final PtRouter ptRouter;
    private final MatrixAPI matrixAPI;
    private Map<String, String> gtfsLinkMappings;
    private Map<String, List<String>> gtfsRouteInfo;
    private Map<String, String> gtfsFeedIdMapping;
    private final StatsDClient statsDClient;
    private Map<String, String> customTags;
    private final ObjectMapper yamlOM = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
    private final ObjectMapper jsonOM = Jackson.newObjectMapper();

    public RouterImpl(GraphHopper graphHopper, PtRouter ptRouter, MatrixAPI matrixAPI,
                      Map<String, String> gtfsLinkMappings,
                      Map<String, List<String>> gtfsRouteInfo,
                      Map<String, String> gtfsFeedIdMapping,
                      StatsDClient statsDClient,
                      String regionName,
                      String releaseName) {
        this.graphHopper = graphHopper;
        this.ptRouter = ptRouter;
        this.matrixAPI = matrixAPI;
        this.gtfsLinkMappings = gtfsLinkMappings;
        this.gtfsRouteInfo = gtfsRouteInfo;
        this.gtfsFeedIdMapping = gtfsFeedIdMapping;
        this.statsDClient = statsDClient;
        this.customTags = Maps.newHashMap();
        customTags.put("replica_region", regionName);
        customTags.put("release_name", releaseName);
    }

    @Override
    public void routeStreetMode(StreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        // For a given "base" profile requested (eg `car`), find all pre-loaded profiles associated
        // with the base profile (eg `car_local`, `car_freeway`). Each such pre-loaded profile will get
        // queried, and resulting paths will be combined in one response
        List<String> profilesToQuery = graphHopper.getProfiles().stream()
                .map(Profile::getName)
                .filter(profile -> profile.startsWith(request.getProfile()))
                .collect(toList());

        // Construct query object with settings shared across all profilesToQuery
        List<GHPoint> pointsList = request.getPointsList().stream()
                .map(p -> new GHPoint(p.getLat(), p.getLon()))
                .collect(Collectors.toList());

        GHRequest ghRequest = new GHRequest(pointsList);
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

        StreetRouteReply.Builder replyBuilder = StreetRouteReply.newBuilder();
        boolean anyPathsFound = false;
        for (String profile : profilesToQuery) {
            ghRequest.setProfile(profile);
            try {
                GHResponse ghResponse = graphHopper.route(ghRequest);
                // ghResponse.hasErrors() means that the router returned no results
                if (!ghResponse.hasErrors()) {
                    anyPathsFound = true;
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
                                .setPoints(responsePath.getPoints().toLineString(false).toString())
                        );
                    }
                }
            } catch (Exception e) {
                String message = "GH internal error! Path could not be found between "
                        + ghRequest.getPoints().get(0).lat + "," + ghRequest.getPoints().get(0).lon + " to "
                        + ghRequest.getPoints().get(1).lat + "," + ghRequest.getPoints().get(1).lon +
                        " using profile " + profile;
                logger.error(message, e);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:error"};
                tags = applyCustomTags(tags, customTags);
                sendDatadogStats(statsDClient, tags, durationSeconds);

                Status status = Status.newBuilder()
                        .setCode(Code.INTERNAL.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            }
        }

        // If no paths were found across any of the queried profiles,
        // return the standard NOT_FOUND grpc error code
        if (!anyPathsFound) {
            String message = "Path could not be found between "
                    + pointsList.get(0).lat + "," + pointsList.get(0).lon + " to "
                    + pointsList.get(1).lat + "," + pointsList.get(1).lon;

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:false"};
            tags = applyCustomTags(tags, customTags);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.NOT_FOUND.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        } else {
            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
            tags = applyCustomTags(tags, customTags);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void routeCustom(CustomRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        GHRequest ghRequest = new GHRequest(
                request.getPointsList().stream().map(p -> new GHPoint(p.getLat(), p.getLon())).collect(Collectors.toList())
        );
        ghRequest.setProfile(request.getProfile());
        ghRequest.setLocale(Locale.US);
        ghRequest.setPathDetails(Lists.newArrayList("stable_edge_ids", "time"));

        PMap hints = new PMap();
        CustomModel customModel;
        try {
            customModel = (request.getCustomModel().startsWith("{") ? jsonOM : yamlOM).readValue(request.getCustomModel(), CustomModel.class);
        } catch (Exception e) {
            logger.error(e.getMessage());
            logger.error(e.getStackTrace().toString());
            throw new RuntimeException("Couldn't read custom model from GH request! Full request: " + request.toString());
        }
        hints.putObject(Parameters.CH.DISABLE, true);
        hints.putObject(CustomModel.KEY, customModel);

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
            if (ghResponse.hasErrors()) {
                logger.error(ghResponse.toString());
                String message = "Path could not be found between "
                        + ghRequest.getPoints().get(0).lat + "," + ghRequest.getPoints().get(0).lon + " to "
                        + ghRequest.getPoints().get(1).lat + "," + ghRequest.getPoints().get(1).lon;
                // logger.warn(message);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:false"};
                tags = applyCustomTags(tags, customTags);
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
                            .setDurationMillis(1)
                            .setDistanceMeters(responsePath.getDistance())
                            .addAllStableEdgeIds(pathStableEdgeIds)
                            .addAllEdgeDurationsMillis(edgeTimes)
                            .setPoints(responsePath.getPoints().toLineString(false).toString())
                    );
                }

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
                tags = applyCustomTags(tags, customTags);
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
            tags = applyCustomTags(tags, customTags);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    // TODO: Clean up code based on fix-it comments in PR #26
    @Override
    public void routeMatrix(MatrixRouteRequest request, StreamObserver<MatrixRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        List<GHPoint> fromPoints = request.getFromPointsList().stream()
                .map(p -> new GHPoint(p.getLat(), p.getLon())).collect(toList());
        List<GHPoint> toPoints = request.getToPointsList().stream()
                .map(p -> new GHPoint(p.getLat(), p.getLon())).collect(toList());

        GHMRequest ghMatrixRequest = new GHMRequest();
        ghMatrixRequest.setFromPoints(fromPoints);
        ghMatrixRequest.setToPoints(toPoints);
        ghMatrixRequest.setOutArrays(new HashSet<>(request.getOutArraysList()));
        ghMatrixRequest.setProfile(request.getMode());
        ghMatrixRequest.setFailFast(request.getFailFast());

        try {
            GHMResponse ghMatrixResponse = matrixAPI.calc(ghMatrixRequest);

            if (ghMatrixRequest.getFailFast() && ghMatrixResponse.hasInvalidPoints()) {
                MatrixErrors matrixErrors = new MatrixErrors();
                matrixErrors.addInvalidFromPoints(ghMatrixResponse.getInvalidFromPoints());
                matrixErrors.addInvalidToPoints(ghMatrixResponse.getInvalidToPoints());
                throw new MatrixCalculationException(matrixErrors);
            }
            int from_len = ghMatrixRequest.getFromPoints().size();
            int to_len = ghMatrixRequest.getToPoints().size();
            List<List<Long>> timeList = new ArrayList(from_len);
            List<Long> timeRow;
            List<List<Long>> distanceList = new ArrayList(from_len);
            List<Long> distanceRow;
            Iterator<MatrixElement> iter = ghMatrixResponse.getMatrixElementIterator();
            MatrixErrors matrixErrors = new MatrixErrors();
            StringBuilder debugBuilder = new StringBuilder();
            debugBuilder.append(ghMatrixResponse.getDebugInfo());

            for(int fromIndex = 0; fromIndex < from_len; ++fromIndex) {
                timeRow = new ArrayList(to_len);
                timeList.add(timeRow);
                distanceRow = new ArrayList(to_len);
                distanceList.add(distanceRow);

                for(int toIndex = 0; toIndex < to_len; ++toIndex) {
                    if (!iter.hasNext()) {
                        throw new IllegalStateException("Internal error, matrix dimensions should be " + from_len + "x" + to_len + ", but failed to retrieve element (" + fromIndex + ", " + toIndex + ")");
                    }

                    MatrixElement element = iter.next();
                    if (!element.isConnected()) {
                        matrixErrors.addDisconnectedPair(element.getFromIndex(), element.getToIndex());
                    }

                    if (ghMatrixRequest.getFailFast() && matrixErrors.hasDisconnectedPairs()) {
                        throw new MatrixCalculationException(matrixErrors);
                    }

                    long time = element.getTime();
                    timeRow.add(time == Long.MAX_VALUE ? -1 : Math.round((double)time / 1000.0D));

                    double distance = element.getDistance();
                    distanceRow.add(distance == Double.MAX_VALUE ? -1 : Math.round(distance));

                    debugBuilder.append(element.getDebugInfo());
                }
            }

            List<MatrixRow> timeRows = timeList.stream()
                    .map(row -> MatrixRow.newBuilder().addAllValues(row).build()).collect(toList());
            List<MatrixRow> distanceRows = distanceList.stream()
                    .map(row -> MatrixRow.newBuilder().addAllValues(row).build()).collect(toList());

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getMode() + "_matrix", "api:grpc", "routes_found:true"};
            tags = applyCustomTags(tags, customTags);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            MatrixRouteReply result = MatrixRouteReply.newBuilder().addAllTimes(timeRows).addAllDistances(distanceRows).build();
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error while completing GraphHopper matrix request! ", e);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getMode() + "_matrix", "api:grpc", "routes_found:false"};
            tags = applyCustomTags(tags, customTags);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage("GH internal error! Matrix request could not be completed.")
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
        ghPtRequest.setBetaWalkTime(request.getBetaWalkTime());
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

                // Replace the path's legs with newly-constructed legs containing stable edge IDs
                ArrayList<Trip.Leg> legs = new ArrayList<>(path.getLegs());
                path.getLegs().clear();

                for (int i = 0; i < legs.size(); i++) {
                    Trip.Leg leg = legs.get(i);
                    if (leg instanceof Trip.WalkLeg) {
                        Trip.WalkLeg thisLeg = (Trip.WalkLeg) leg;
                        String travelSegmentType;
                        // We only expect graphhopper to return ACCESS + EGRESS walk legs
                        if (i == 0) {
                            travelSegmentType = "ACCESS";
                        } else {
                            travelSegmentType = "EGRESS";
                        }
                        path.getLegs().add(new CustomWalkLeg(thisLeg, fetchWalkLegStableIds(thisLeg), travelSegmentType));
                    } else if (leg instanceof Trip.PtLeg) {
                        Trip.PtLeg thisLeg = (Trip.PtLeg) leg;
                        path.getLegs().add(getCustomPtLeg(thisLeg));

                        // If this PT leg is followed by another PT leg, add a TRANSFER walk leg between them
                        if (i < legs.size() - 1 && legs.get(i + 1) instanceof Trip.PtLeg) {
                            Trip.PtLeg nextLeg = (Trip.PtLeg) legs.get(i + 1);
                            Trip.Stop lastStopOfThisLeg = thisLeg.stops.get(thisLeg.stops.size() - 1);
                            Trip.Stop firstStopOfNextLeg = nextLeg.stops.get(0);
                            if (!lastStopOfThisLeg.stop_id.equals(firstStopOfNextLeg.stop_id)) {
                                GHRequest r = new GHRequest(
                                        lastStopOfThisLeg.geometry.getY(), lastStopOfThisLeg.geometry.getX(),
                                        firstStopOfNextLeg.geometry.getY(), firstStopOfNextLeg.geometry.getX());
                                r.setProfile("foot");
                                r.setPathDetails(Arrays.asList("stable_edge_ids"));
                                GHResponse transfer = graphHopper.route(r);
                                if (!transfer.hasErrors()) {
                                    ResponsePath transferPath = transfer.getBest();
                                    Trip.WalkLeg transferLeg = new Trip.WalkLeg(
                                            lastStopOfThisLeg.stop_name,
                                            thisLeg.getArrivalTime(),
                                            transferPath.getPoints().getCachedLineString(false),
                                            transferPath.getDistance(),
                                            transferPath.getInstructions(),
                                            transferPath.getPathDetails(),
                                            Date.from(thisLeg.getArrivalTime().toInstant().plusMillis(transferPath.getTime()))
                                    );
                                    path.getLegs().add(new CustomWalkLeg(transferLeg, fetchWalkLegStableIds(transferLeg), "TRANSFER"));
                                }
                            }
                        }
                    }
                }

                // ACCESS legs contains stable IDs for both ACCESS and EGRESS legs for some reason,
                // so we remove the EGRESS leg IDs from the ACCESS leg before storing the path
                CustomWalkLeg accessLeg = (CustomWalkLeg) path.getLegs().get(0);
                CustomWalkLeg egressLeg = (CustomWalkLeg) path.getLegs().get(path.getLegs().size() - 1);
                accessLeg.stableEdgeIds.removeAll(egressLeg.stableEdgeIds);

                // Calculate correct distance incorporating foot + pt legs
                path.setDistance(path.getLegs().stream().mapToDouble(l -> l.distance).sum());

                path.getPathDetails().clear();
                pathsWithStableIds.add(path);
            }

            if (pathsWithStableIds.size() == 0) {
                String message = "Transit path could not be found between " + fromPoint.getLat() + "," +
                        fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon();
                // logger.warn(message);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:pt", "api:grpc", "routes_found:false"};
                tags = applyCustomTags(tags, customTags);
                sendDatadogStats(statsDClient, tags, durationSeconds);

                Status status = Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            } else {
                PtRouteReply.Builder replyBuilder = PtRouteReply.newBuilder();
                for (ResponsePath responsePath : pathsWithStableIds) {
                    List<PtLeg> legs = responsePath.getLegs().stream()
                            .map(RouterImpl::createPtLeg)
                            .collect(toList());

                    replyBuilder.addPaths(PtPath.newBuilder()
                            .setDurationMillis(responsePath.getTime())
                            .setDistanceMeters(responsePath.getDistance())
                            .setTransfers(responsePath.getNumChanges())
                            .addAllLegs(legs)
                    );
                }

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:pt", "api:grpc", "routes_found:true"};
                tags = applyCustomTags(tags, customTags);
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
            tags = applyCustomTags(tags, customTags);
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
            tags = applyCustomTags(tags, customTags);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage("GH internal error! Path could not be found between " + fromPoint.getLat() + "," +
                            fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon())
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    private static List<String> fetchWalkLegStableIds(Trip.WalkLeg leg) {
        return leg.details.get("stable_edge_ids").stream()
                .map(idPathDetail -> (String) idPathDetail.getValue())
                .filter(id -> id.length() == 20)
                .collect(toList());
    }

    private static PtLeg createPtLeg(Trip.Leg leg) {
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

    private CustomPtLeg getCustomPtLeg(Trip.PtLeg leg) {
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

            if (STREET_BASED_ROUTE_TYPES.contains(Integer.parseInt(routeType))) {
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

    private static String gtfsRouteInfoKey(Trip.PtLeg leg) {
        return leg.feed_id + ":" + leg.route_id;
    }

    private static void sendDatadogStats(StatsDClient statsDClient, String[] tags, double durationSeconds) {
        if (statsDClient != null) {
            statsDClient.incrementCounter("routers.num_requests", tags);
            statsDClient.distribution("routers.request_seconds", durationSeconds, tags);
        }
    }

    // Apply region + helm release tags, if they exist
    private static String[] applyCustomTags(String[] tags, Map<String, String> customTags) {
        for (String tagName : customTags.keySet()) {
            tags = applyTag(tags, tagName, customTags.get(tagName));
        }
        return tags;
    }

    private static String[] applyTag(String[] tags, String tagName, String tagValue) {
        if (tagValue == null) {
            return tags;
        } else {
            List<String> newTags = Lists.newArrayList(tags);
            newTags.add(tagName + ":" + tagValue);
            return newTags.toArray(new String[newTags.size()]);
        }
    }
}

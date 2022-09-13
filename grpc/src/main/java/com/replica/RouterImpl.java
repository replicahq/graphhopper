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
import com.google.common.collect.Maps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.*;
import com.graphhopper.config.Profile;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.routing.GHMRequest;
import com.graphhopper.routing.GHMResponse;
import com.graphhopper.routing.MatrixAPI;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;
import com.replica.util.MetricUtils;
import com.replica.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.*;

import java.util.*;

import static java.util.stream.Collectors.toList;

public class RouterImpl extends router.RouterGrpc.RouterImplBase {

    private static final Logger logger = LoggerFactory.getLogger(RouterImpl.class);
    private final GraphHopper graphHopper;
    private final PtRouter ptRouter;
    private final MatrixAPI matrixAPI;
    private Map<String, String> gtfsLinkMappings;
    private Map<String, List<String>> gtfsRouteInfo;
    private Map<String, String> gtfsFeedIdMapping;
    private final StatsDClient statsDClient;
    private Map<String, String> customTags;

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
        GHRequest ghRequest = RouterConverters.toGHRequest(request);

        GHPoint origin = ghRequest.getPoints().get(0);
        GHPoint dest = ghRequest.getPoints().get(1);

        StreetRouteReply.Builder replyBuilder = StreetRouteReply.newBuilder();
        boolean anyPathsFound = false;
        for (String profile : profilesToQuery) {
            ghRequest.setProfile(profile);
            try {
                GHResponse ghResponse = graphHopper.route(ghRequest);
                // ghResponse.hasErrors() means that the router returned no results
                if (!ghResponse.hasErrors()) {
                    anyPathsFound = true;
                    ghResponse.getAll().stream()
                            .map(RouterConverters::toStreetPath)
                            .forEach(replyBuilder::addPaths);
                }
            } catch (Exception e) {
                String message = "GH internal error! Path could not be found between "
                        + origin.lat + "," + origin.lon + " to " + dest.lat + "," + dest.lon +
                        " using profile " + profile;
                logger.error(message, e);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:error"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

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
                    + origin.lat + "," + origin.lon + " to "
                    + dest.lat + "," + dest.lon;

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:false"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.NOT_FOUND.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        } else {
            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        }
    }

    // TODO: Clean up code based on fix-it comments in PR #26
    @Override
    public void routeMatrix(MatrixRouteRequest request, StreamObserver<MatrixRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();
        GHMRequest ghMatrixRequest = RouterConverters.toGHMRequest(request);

        try {
            GHMResponse ghMatrixResponse = matrixAPI.calc(ghMatrixRequest);
            MatrixRouteReply result = RouterConverters.toMatrixRouteReply(ghMatrixResponse, ghMatrixRequest);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getMode() + "_matrix", "api:grpc", "routes_found:true"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error while completing GraphHopper matrix request! ", e);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getMode() + "_matrix", "api:grpc", "routes_found:false"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

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
        Request ghPtRequest = RouterConverters.toGHPtRequest(request);

        try {
            GHResponse ghResponse = ptRouter.route(ghPtRequest);
            List<ResponsePath> pathsWithStableIds = Lists.newArrayList();
            for (ResponsePath path : ghResponse.getAll()) {
                // Ignore walking-only responses, because we route those separately from PT
                if (path.getLegs().size() == 1 && path.getLegs().get(0).type.equals("walk")) {
                    continue;
                }

                augmentLegsForPt(path);
                pathsWithStableIds.add(path);
            }

            if (pathsWithStableIds.size() == 0) {
                String message = "Transit path could not be found between " + fromPoint.getLat() + "," +
                        fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon();
                // logger.warn(message);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:pt", "api:grpc", "routes_found:false"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

                Status status = Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            } else {
                PtRouteReply.Builder replyBuilder = PtRouteReply.newBuilder();
                pathsWithStableIds.stream()
                        .map(RouterConverters::toPtPath)
                        .forEach(replyBuilder::addPaths);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:pt", "api:grpc", "routes_found:true"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

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
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.NOT_FOUND.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        } catch (Exception e) {
            logger.error("GraphHopper internal error! ", e);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:pt", "api:grpc", "routes_found:error"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage("GH internal error! Path could not be found between " + fromPoint.getLat() + "," +
                            fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon())
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    /**
     * Performs public-transit-specific modifications to the legs of the ResponsePath. Specifically:
     *
     * - adds transfer walk legs between PT legs where necessary (and updates the path distance accordingly)
     * - adds stable edge ids to the walk and PT legs
     * - stores ACCESS/EGRESS metadata on walk legs
     *
     * @param path the ResponsePath to augment. modified in place
     */
    private void augmentLegsForPt(ResponsePath path) {
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
                path.getLegs().add(RouterConverters.toCustomWalkLeg(thisLeg, travelSegmentType));
            } else if (leg instanceof Trip.PtLeg) {
                Trip.PtLeg thisLeg = (Trip.PtLeg) leg;
                path.getLegs().add(RouterConverters.toCustomPtLeg(thisLeg, gtfsFeedIdMapping, gtfsLinkMappings, gtfsRouteInfo));

                // If this PT leg is followed by another PT leg, add a TRANSFER walk leg between them
                if (i < legs.size() - 1 && legs.get(i + 1) instanceof Trip.PtLeg) {
                    maybeGetTransferWalkLeg(thisLeg, (Trip.PtLeg) legs.get(i + 1)).ifPresent(path.getLegs()::add);
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
    }

    /**
     * @return a transfer walk leg between two consecutive legs of a PT trip, if necessary. returns an empty optional if
     * the first leg ends where the second begins, or if a walking route could not be found.
     */
    private Optional<CustomWalkLeg> maybeGetTransferWalkLeg(Trip.PtLeg firstLeg, Trip.PtLeg secondLeg) {
        Trip.Stop lastStopOfThisLeg = firstLeg.stops.get(firstLeg.stops.size() - 1);
        Trip.Stop firstStopOfNextLeg = secondLeg.stops.get(0);
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
                        firstLeg.getArrivalTime(),
                        transferPath.getPoints().getCachedLineString(false),
                        transferPath.getDistance(),
                        transferPath.getInstructions(),
                        transferPath.getPathDetails(),
                        Date.from(firstLeg.getArrivalTime().toInstant().plusMillis(transferPath.getTime()))
                );
                return Optional.of(RouterConverters.toCustomWalkLeg(transferLeg, "TRANSFER"));
            }
        }

        return Optional.empty();
    }
}

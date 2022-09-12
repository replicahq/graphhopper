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

package com.replica.router;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.*;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.routing.GHMRequest;
import com.graphhopper.routing.GHMResponse;
import com.graphhopper.routing.MatrixAPI;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.replica.router.util.MetricUtils;
import com.replica.router.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.*;

import java.util.*;

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
        GHRequest ghRequest = RouterConverters.toGHRequest(request);

        try {
            GHResponse ghResponse = graphHopper.route(ghRequest);
            if (ghResponse.hasErrors()) {
                String message = "Path could not be found between "
                        + ghRequest.getPoints().get(0).lat + "," + ghRequest.getPoints().get(0).lon + " to "
                        + ghRequest.getPoints().get(1).lat + "," + ghRequest.getPoints().get(1).lon;
                // logger.warn(message);

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
                StreetRouteReply.Builder replyBuilder = StreetRouteReply.newBuilder();
                ghResponse.getAll().stream()
                        .map(RouterConverters::toStreetPath)
                        .forEach(replyBuilder::addPaths);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

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
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendDatadogStats(statsDClient, tags, durationSeconds);

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
                                    path.getLegs().add(RouterConverters.toCustomWalkLeg(transferLeg, "TRANSFER"));
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
}

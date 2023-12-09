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

import com.google.common.collect.Maps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.isochrone.algorithm.JTSTriangulator;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.shapes.GHPoint;
import com.replica.api.IsochroneRouter;
import com.replica.api.StreetRouter;
import com.replica.api.TransitIsochroneRouter;
import com.replica.api.TransitRouter;
import com.replica.util.MetricUtils;
import com.replica.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class RouterImpl extends router.RouterGrpc.RouterImplBase {
    private static final Logger logger = LoggerFactory.getLogger(RouterImpl.class);
    private final GraphHopper graphHopper;
    private final StatsDClient statsDClient;
    private Map<String, String> customTags;

    private StreetRouter streetRouter;
    private TransitRouter transitRouter;
    private IsochroneRouter isochroneRouter;
    private TransitIsochroneRouter transitIsochroneRouter;

    public RouterImpl(GraphHopper graphHopper, PtRouter ptRouter,
                      Map<String, String> gtfsLinkMappings,
                      Map<String, List<String>> gtfsRouteInfo,
                      Map<String, String> gtfsFeedIdMapping,
                      StatsDClient statsDClient,
                      String regionName,
                      String releaseName) {
        this.graphHopper = graphHopper;
        this.statsDClient = statsDClient;
        this.customTags = Maps.newHashMap();
        customTags.put("replica_region", regionName);
        customTags.put("release_name", releaseName);

        this.streetRouter = new StreetRouter(graphHopper, statsDClient, customTags);
        this.isochroneRouter = new IsochroneRouter(graphHopper, new JTSTriangulator(graphHopper.getRouterConfig()));

        if (ptRouter != null) {
            this.transitRouter = new TransitRouter(ptRouter, gtfsLinkMappings, gtfsRouteInfo, gtfsFeedIdMapping, statsDClient, customTags);
            this.transitIsochroneRouter = new TransitIsochroneRouter((GraphHopperGtfs) graphHopper);
        }
    }

    @Override
    public void routeCustom(CustomRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();
        GHRequest ghRequest = RouterConverters.toGHRequest(request);
        GHPoint origin = ghRequest.getPoints().get(0);
        GHPoint dest = ghRequest.getPoints().get(1);

        try {
            GHResponse ghResponse = graphHopper.route(ghRequest);
            if (ghResponse.hasErrors()) {
                logger.error(ghResponse.toString());
                String message = "Path could not be found between "
                        + origin.lat + "," + origin.lon + " to " + dest.lat + "," + dest.lon;
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
                        .map(responsePath -> RouterConverters.toStreetPath(responsePath, request.getProfile(), request.getReturnFullPathDetails()))
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
                    + origin.lat + "," + origin.lon + " to " + dest.lat + "," + dest.lon;
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

    @Override
    public void info(InfoRequest request, StreamObserver<InfoReply> responseObserver) {
        BaseGraph baseGraph = graphHopper.getBaseGraph();
        responseObserver.onNext(InfoReply.newBuilder().addAllBbox(
                Arrays.asList(
                        baseGraph.getBounds().minLon, baseGraph.getBounds().minLat,
                        baseGraph.getBounds().maxLon, baseGraph.getBounds().maxLat
                )).build());
        responseObserver.onCompleted();
    }

    @Override
    public void routeStreetMode(StreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        streetRouter.routeStreetMode(request, responseObserver);
    }

    @Override
    public void routePt(PtRouteRequest request, StreamObserver<PtRouteReply> responseObserver) {
        transitRouter.routePt(request, responseObserver);
    }

    @Override
    public void routeIsochrone(IsochroneRouteRequest request, StreamObserver<IsochroneRouteReply> responseObserver) {
        isochroneRouter.routeIsochrone(request, responseObserver);
    }

    @Override
    public void routePtIsochrone(PtIsochroneRouteRequest request, StreamObserver<IsochroneRouteReply> responseObserver) {
        transitIsochroneRouter.routePtIsochrone(request, responseObserver);
    }
}

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
import com.graphhopper.GraphHopper;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.isochrone.algorithm.JTSTriangulator;
import com.graphhopper.storage.BaseGraph;
import com.replica.api.*;
import com.timgroup.statsd.StatsDClient;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import router.RouterOuterClass.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class RouterImpl extends router.RouterGrpc.RouterImplBase {
    private final GraphHopper graphHopper;

    private StreetRouter streetRouter;
    private CustomStreetRouter customStreetRouter;
    private IsochroneRouter isochroneRouter;
    private TransitRouter transitRouter;
    private TransitIsochroneRouter transitIsochroneRouter;

    public RouterImpl(GraphHopper graphHopper, PtRouter ptRouter,
                      Map<String, String> gtfsLinkMappings,
                      Map<String, List<String>> gtfsRouteInfo,
                      Map<String, String> gtfsFeedIdMapping,
                      StatsDClient statsDClient,
                      String regionName,
                      String releaseName) {
        this.graphHopper = graphHopper;
        Map<String, String> customTags = Maps.newHashMap();
        customTags.put("replica_region", regionName);
        customTags.put("release_name", releaseName);

        this.streetRouter = new StreetRouter(graphHopper, statsDClient, customTags);
        this.customStreetRouter = new CustomStreetRouter(graphHopper, statsDClient, customTags);
        this.isochroneRouter = new IsochroneRouter(graphHopper, new JTSTriangulator(graphHopper.getRouterConfig()));

        if (ptRouter != null) {
            this.transitRouter = new TransitRouter(ptRouter, gtfsLinkMappings, gtfsRouteInfo, gtfsFeedIdMapping, statsDClient, customTags);
            this.transitIsochroneRouter = new TransitIsochroneRouter((GraphHopperGtfs) graphHopper);
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
    public void routeCustom(CustomRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        customStreetRouter.routeCustom(request, responseObserver);
    }

    @Override
    public void routeIsochrone(IsochroneRouteRequest request, StreamObserver<IsochroneRouteReply> responseObserver) {
        isochroneRouter.routeIsochrone(request, responseObserver);
    }

    @Override
    public void routePt(PtRouteRequest request, StreamObserver<PtRouteReply> responseObserver) {
        if (transitRouter != null) {
            transitRouter.routePt(request, responseObserver);
        } else {
            responseObserver.onError(buildUnavailableEndpointException(
                    "Transit routing is not available! This router was not built with any GTFS"
            ));
        }
    }

    @Override
    public void routePtIsochrone(PtIsochroneRouteRequest request, StreamObserver<IsochroneRouteReply> responseObserver) {
        if (transitIsochroneRouter != null) {
            transitIsochroneRouter.routePtIsochrone(request, responseObserver);
        } else {
            responseObserver.onError(buildUnavailableEndpointException(
                    "Transit isochrone routing is not available! This router was not built with any GTFS"
            ));
        }
    }

    private static StatusRuntimeException buildUnavailableEndpointException(String message) {
        Status status = Status.newBuilder()
                .setCode(Code.UNAVAILABLE.getNumber())
                .setMessage(message)
                .build();
        return StatusProto.toStatusRuntimeException(status);
    }
}

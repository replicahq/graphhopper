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

import com.graphhopper.GraphHopper;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.isochrone.algorithm.JTSTriangulator;
import com.graphhopper.routing.MatrixAPI;
import com.graphhopper.storage.GraphHopperStorage;
import com.replica.api.*;
import com.timgroup.statsd.StatsDClient;
import io.grpc.stub.StreamObserver;
import router.RouterOuterClass.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class RouterImpl extends router.RouterGrpc.RouterImplBase {

    private final GraphHopper graphHopper;

    private StreetRouter streetRouter;
    private TransitRouter transitRouter;
    private MatrixRouter matrixRouter;
    private IsochroneRouter isochroneRouter;
    private TransitIsochroneRouter transitIsochroneRouter;

    public RouterImpl(GraphHopper graphHopper, PtRouter ptRouter, MatrixAPI matrixAPI,
                      Map<String, String> gtfsLinkMappings,
                      Map<String, List<String>> gtfsRouteInfo,
                      Map<String, String> gtfsFeedIdMapping,
                      StatsDClient statsDClient,
                      String regionName) {
        this.graphHopper = graphHopper;
        this.streetRouter = new StreetRouter(graphHopper, statsDClient, regionName);
        this.transitRouter = new TransitRouter(graphHopper, ptRouter, gtfsLinkMappings,
                gtfsRouteInfo, gtfsFeedIdMapping, statsDClient, regionName);
        this.matrixRouter = new MatrixRouter(matrixAPI, statsDClient, regionName);
        this.isochroneRouter = new IsochroneRouter(graphHopper, new JTSTriangulator(graphHopper.getRouterConfig()));
        this.transitIsochroneRouter = new TransitIsochroneRouter(graphHopper);
    }

    @Override
    public void info(InfoRequest request, StreamObserver<InfoReply> responseObserver) {
        GraphHopperStorage storage = graphHopper.getGraphHopperStorage();
        responseObserver.onNext(InfoReply.newBuilder().addAllBbox(Arrays.asList(storage.getBounds().minLon, storage.getBounds().minLat, storage.getBounds().maxLon, storage.getBounds().maxLat)).build());
        responseObserver.onCompleted();
    }

    @Override
    public void routeStreetMode(StreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        streetRouter.routeStreetMode(request, responseObserver);
    }

    @Override
    public void routeMatrix(router.RouterOuterClass.MatrixRouteRequest request, StreamObserver<router.RouterOuterClass.MatrixRouteReply> responseObserver) {
        matrixRouter.routeMatrix(request, responseObserver);
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

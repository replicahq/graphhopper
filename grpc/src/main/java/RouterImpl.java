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

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.shapes.GHPoint;
import io.grpc.stub.StreamObserver;

import java.util.stream.Collectors;

public class RouterImpl extends RouterGrpc.RouterImplBase {

    private final GraphHopper graphHopper;

    public RouterImpl(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    @Override
    public void route(Route.RouteRequest request, StreamObserver<Route.RouteReply> responseObserver) {
        GHRequest ghRequest = new GHRequest(
                request.getPointsList().stream().map(p -> new GHPoint(p.getLat(), p.getLon())).collect(Collectors.toList())
        );
        ghRequest.setProfile(request.getProfile());
        GHResponse ghResponse = graphHopper.route(ghRequest);
        Route.RouteReply.Builder replyBuilder = Route.RouteReply.newBuilder();
        for (ResponsePath responsePath : ghResponse.getAll()) {
            replyBuilder.addPaths(Route.Path.newBuilder().setTime(responsePath.getTime()));
        }
        responseObserver.onNext(replyBuilder.build());
        responseObserver.onCompleted();
    }

}

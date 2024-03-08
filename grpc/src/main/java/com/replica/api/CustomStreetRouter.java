package com.replica.api;

import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.util.shapes.GHPoint;
import com.replica.util.MetricUtils;
import com.replica.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass;

import java.util.Map;

public class CustomStreetRouter {

    private static final Logger logger = LoggerFactory.getLogger(CustomStreetRouter.class);
    private final GraphHopper graphHopper;
    private final StatsDClient statsDClient;
    private Map<String, String> customTags;

    public CustomStreetRouter(GraphHopper graphHopper,
                        StatsDClient statsDClient,
                        Map<String, String> customTags) {
        this.graphHopper = graphHopper;
        this.statsDClient = statsDClient;
        this.customTags = customTags;
    }

    public void routeCustom(RouterOuterClass.CustomRouteRequest request, StreamObserver<RouterOuterClass.StreetRouteReply> responseObserver) {
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
                MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, 0);

                Status status = Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            } else {
                RouterOuterClass.StreetRouteReply.Builder replyBuilder = RouterOuterClass.StreetRouteReply.newBuilder();
                ghResponse.getAll().stream()
                        .map(responsePath -> RouterConverters.toStreetPath(responsePath, request.getProfile(), request.getReturnFullPathDetails()))
                        .forEach(replyBuilder::addPaths);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, ghResponse.getAll().size());

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
            MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }
}

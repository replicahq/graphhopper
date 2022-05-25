package com.replica.api;

import com.google.common.collect.Lists;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.PMap;
import com.graphhopper.util.shapes.GHPoint;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.graphhopper.util.Parameters.Routing.INSTRUCTIONS;
import static com.replica.metrics.MetricsHelper.applyRegionName;
import static com.replica.metrics.MetricsHelper.sendDatadogStats;

public class StreetRouter {

    private static final Logger logger = LoggerFactory.getLogger(StreetRouter.class);
    private final GraphHopper graphHopper;
    private final StatsDClient statsDClient;
    private String regionName;

    public StreetRouter(GraphHopper graphHopper,
                        StatsDClient statsDClient,
                        String regionName) {
        this.graphHopper = graphHopper;
        this.statsDClient = statsDClient;
        this.regionName = regionName;
    }

    public void routeStreetMode(RouterOuterClass.StreetRouteRequest request, StreamObserver<RouterOuterClass.StreetRouteReply> responseObserver) {
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
            if (ghResponse.hasErrors()) {
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
                RouterOuterClass.StreetRouteReply.Builder replyBuilder = RouterOuterClass.StreetRouteReply.newBuilder();
                for (ResponsePath responsePath : ghResponse.getAll()) {
                    List<String> pathStableEdgeIds = responsePath.getPathDetails().get("stable_edge_ids").stream()
                            .map(pathDetail -> (String) pathDetail.getValue())
                            .collect(Collectors.toList());

                    List<Long> edgeTimes = responsePath.getPathDetails().get("time").stream()
                            .map(pathDetail -> (Long) pathDetail.getValue())
                            .collect(Collectors.toList());

                    replyBuilder.addPaths(RouterOuterClass.StreetPath.newBuilder()
                            .setDurationMillis(responsePath.getTime())
                            .setDistanceMeters(responsePath.getDistance())
                            .addAllStableEdgeIds(pathStableEdgeIds)
                            .addAllEdgeDurationsMillis(edgeTimes)
                            .setPoints(responsePath.getPoints().toLineString(false).toString())
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
}

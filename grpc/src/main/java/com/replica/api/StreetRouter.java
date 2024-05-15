package com.replica.api;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.*;
import com.graphhopper.config.Profile;
import com.graphhopper.util.shapes.GHPoint;
import com.replica.util.MetricUtils;
import com.replica.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.glassfish.jersey.internal.guava.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.StreetRouteReply;
import router.RouterOuterClass.StreetRouteRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.replica.util.RouterConverters.getEdgeTimes;
import static com.replica.util.RouterConverters.getPathStableEdgeIds;

public class StreetRouter {

    private static final Logger logger = LoggerFactory.getLogger(StreetRouter.class);
    private final GraphHopper graphHopper;
    private final StatsDClient statsDClient;
    private Map<String, String> customTags;

    public StreetRouter(GraphHopper graphHopper,
                        StatsDClient statsDClient,
                        Map<String, String> customTags) {
        this.graphHopper = graphHopper;
        this.statsDClient = statsDClient;
        this.customTags = customTags;
    }

    public static Long calculatePathId(ResponsePath responsePath) {
        return calculatePathId(getPathStableEdgeIds(responsePath), getEdgeTimes(responsePath));
    }

    public static Long calculatePathId(List<String> pathStableEdgeIds, List<Long> pathEdgeTimes) {
        String pathStableEdgeIdsString = String.join(",", pathStableEdgeIds);
        String edgeTimeString = pathEdgeTimes.stream().map(Object::toString).collect(Collectors.joining(","));
        String hashString = pathStableEdgeIdsString + edgeTimeString;
        HashCode hc = Hashing.farmHashFingerprint64().hashString(hashString, Charsets.UTF_8);
        return hc.asLong();
    }

    public void routeStreetMode(StreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        // For a given "base" profile requested (eg `car`), find all pre-loaded profiles associated
        // with the base profile (eg `car_local`, `car_freeway`). Each such pre-loaded profile will get
        // queried, and resulting paths will be combined in one response
        List<String> profilesToQuery = graphHopper.getProfiles().stream()
                .map(Profile::getName)
                .filter(profile -> profile.startsWith(request.getProfile()))
                .collect(Collectors.toList());

        // Construct query object with settings shared across all profilesToQuery
        GHRequest ghRequest = RouterConverters.toGHRequest(request);

        GHPoint origin = ghRequest.getPoints().get(0);
        GHPoint dest = ghRequest.getPoints().get(1);

        StreetRouteReply.Builder replyBuilder = StreetRouteReply.newBuilder();
        int pathsFound = 0;
        Set<Long> pathIdsInReturnSet = Sets.newHashSet();
        for (String profile : profilesToQuery) {
            ghRequest.setProfile(profile);
            try {
                GHResponse ghResponse = graphHopper.route(ghRequest);
                // ghResponse.hasErrors() means that the router returned no results
                if (!ghResponse.hasErrors()) {
                    List<ResponsePath> pathsToReturn;
                    if (request.getIncludeDuplicateRoutes()) {
                        pathsToReturn = ghResponse.getAll();
                    } else {
                        // Filter out duplicate paths by removing those with path ID
                        // matching a path that's already in return set
                        pathsToReturn = Lists.newArrayList();
                        for (ResponsePath responsePath : ghResponse.getAll()) {
                            Long pathId = calculatePathId(responsePath);
                            if (!pathIdsInReturnSet.contains(pathId)) {
                                pathsToReturn.add(responsePath);
                                pathIdsInReturnSet.add(pathId);
                            }
                        }
                    }
                    pathsFound += pathsToReturn.size();

                    // Add filtered set of paths to full response set
                    pathsToReturn.stream()
                            .map(responsePath -> RouterConverters.toStreetPath(responsePath, profile, request.getReturnFullPathDetails()))
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
                MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds);

                Status status = Status.newBuilder()
                        .setCode(Code.INTERNAL.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            }
        }

        // If no paths were found across any of the queried profiles,
        // return the standard NOT_FOUND grpc error code
        if (pathsFound == 0) {
            String message = "Path could not be found between "
                    + origin.lat + "," + origin.lon + " to " + dest.lat + "," + dest.lon;

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
            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getProfile(), "api:grpc", "routes_found:true"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, pathsFound);

            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        }
    }
}

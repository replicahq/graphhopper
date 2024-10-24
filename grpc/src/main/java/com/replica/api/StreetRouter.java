package com.replica.api;

import com.google.common.collect.Lists;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.replica.util.MetricUtils;
import com.replica.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.glassfish.jersey.internal.guava.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.Point;
import router.RouterOuterClass.ProfilesStreetRouteRequest;
import router.RouterOuterClass.StreetRouteReply;
import router.RouterOuterClass.StreetRouteRequest;

import java.util.*;
import java.util.stream.Collectors;

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

    public void routeStreetMode(StreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        ProfilesStreetRouteRequest profilesStreetRouteRequest = RouterConverters.toProfilesStreetRouteRequest(request, graphHopper);
        // for backcompat, use a metric tag of "mode". clients typically send modes within the profile field, and these
        // are translated into profiles using prefix matching
        String profilesMetricTag = "mode:" + request.getProfile();
        routeStreetProfiles(profilesStreetRouteRequest, responseObserver, profilesMetricTag);
    }

    public void routeStreetProfiles(ProfilesStreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        List<String> orderedRequestedProfiles = new ArrayList<>(request.getProfilesList());
        Collections.sort(orderedRequestedProfiles);
        String profilesMetricTag = "profiles:" + orderedRequestedProfiles;

        routeStreetProfiles(request, responseObserver, profilesMetricTag);
    }

    private void routeStreetProfiles(ProfilesStreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver,
                                     String profilesMetricTag) {
        long startTime = System.currentTimeMillis();
        validateRequest(request, responseObserver);

        Point origin = request.getPoints(0);
        Point dest = request.getPoints(1);

        StreetRouteReply.Builder replyBuilder = StreetRouteReply.newBuilder();
        int pathsFound = 0;
        Set<Integer> pathHashesInReturnSet = Sets.newHashSet();
        for (GHRequest ghRequest : RouterConverters.toGHRequests(request)) {
            try {
                GHResponse ghResponse = graphHopper.route(ghRequest);
                // ghResponse.hasErrors() means that the router returned no results
                if (!ghResponse.hasErrors()) {
                    List<ResponsePath> pathsToReturn;
                    if (request.getIncludeDuplicateRoutes()) {
                        pathsToReturn = ghResponse.getAll();
                    } else {
                        // Filter out duplicate paths by removing those with point lists
                        // whose hashcode matches a path that's already in return set.
                        // Note: we store a hash rather than a full PointList object because
                        // the latter causes a blowup in memory usage
                        pathsToReturn = Lists.newArrayList();
                        for (ResponsePath responsePath : ghResponse.getAll()) {
                            if (!pathHashesInReturnSet.contains(responsePath.getPoints().hashCode())) {
                                pathsToReturn.add(responsePath);
                                pathHashesInReturnSet.add(responsePath.getPoints().hashCode());
                            }
                        }
                    }
                    pathsFound += pathsToReturn.size();

                    // Add filtered set of paths to full response set
                    pathsToReturn.stream()
                            .map(responsePath -> RouterConverters.toStreetPath(responsePath, ghRequest.getProfile(), request.getReturnFullPathDetails()))
                            .forEach(replyBuilder::addPaths);
                }
            } catch (Exception e) {
                String message = "GH internal error! Path could not be found between "
                        + origin.getLat() + "," + origin.getLon() + " to " + dest.getLat() + "," + dest.getLon() +
                        " using profile " + ghRequest.getProfile();
                logger.error(message, e);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = {profilesMetricTag, "api:grpc", "routes_found:error"};
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
                    + origin.getLat() + "," + origin.getLon() + " to " + dest.getLat() + "," + dest.getLon();

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {profilesMetricTag, "api:grpc", "routes_found:false"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, 0);

            Status status = Status.newBuilder()
                    .setCode(Code.NOT_FOUND.getNumber())
                    .setMessage(message)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        } else {
            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {profilesMetricTag, "api:grpc", "routes_found:true"};
            tags = MetricUtils.applyCustomTags(tags, customTags);
            MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, pathsFound);

            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        }
    }

    private void validateRequest(ProfilesStreetRouteRequest request, StreamObserver<StreetRouteReply> responseObserver) {
        Set<String> knownProfiles = graphHopper.getProfiles().stream().map(Profile::getName).collect(Collectors.toSet());
        Set<String> unknownProfiles = new HashSet<>(request.getProfilesList());
        unknownProfiles.removeAll(knownProfiles);
        if (!unknownProfiles.isEmpty()) {
            Status status = Status.newBuilder()
                    .setCode(Code.INVALID_ARGUMENT.getNumber())
                    .setMessage("Requested unknown profiles: " + unknownProfiles)
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }
}

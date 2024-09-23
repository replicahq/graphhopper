package com.replica.api;

import com.google.common.collect.Lists;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.Trip;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.replica.CustomPtLeg;
import com.replica.CustomStreetLeg;
import com.replica.util.MetricUtils;
import com.replica.util.RouterConverters;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass.Point;
import router.RouterOuterClass.PtRouteReply;
import router.RouterOuterClass.PtRouteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TransitRouter {
    private static final Logger logger = LoggerFactory.getLogger(TransitRouter.class);
    private final PtRouter ptRouter;
    private Map<String, String> gtfsLinkMappings;
    private Map<String, List<String>> gtfsRouteInfo;
    private Map<String, String> gtfsFeedIdMapping;
    private final StatsDClient statsDClient;
    private Map<String, String> customTags;

    public TransitRouter(PtRouter ptRouter,
                         Map<String, String> gtfsLinkMappings,
                         Map<String, List<String>> gtfsRouteInfo,
                         Map<String, String> gtfsFeedIdMapping,
                         StatsDClient statsDClient,
                         Map<String, String> customTags) {
        this.ptRouter = ptRouter;
        this.gtfsLinkMappings = gtfsLinkMappings;
        this.gtfsRouteInfo = gtfsRouteInfo;
        this.gtfsFeedIdMapping = gtfsFeedIdMapping;
        this.statsDClient = statsDClient;
        this.customTags = customTags;
    }

    public void routePt(PtRouteRequest request, StreamObserver<PtRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        Point fromPoint = request.getPoints(0);
        Point toPoint = request.getPoints(1);
        Request ghPtRequest = RouterConverters.toGHPtRequest(request);

        try {
            long routeStartTime = System.currentTimeMillis();
            GHResponse ghResponse = ptRouter.route(ghPtRequest);
            double routeDuration = (System.currentTimeMillis() - routeStartTime) / 1000.0;
            String[] tags = MetricUtils.applyCustomTags(new String[0], customTags);
            MetricUtils.sendInternalRoutingStats(statsDClient, tags, routeDuration, "internal_duration");

            long augmentStartTime = System.currentTimeMillis();
            List<ResponsePath> pathsWithStableIds = Lists.newArrayList();
            for (ResponsePath path : ghResponse.getAll()) {
                // Ignore walking-only responses, because we route those separately from PT
                if (path.getLegs().size() == 1 && path.getLegs().get(0).type.equals("walk")) {
                    continue;
                }
                augmentLegsForPt(path, ghPtRequest);
                pathsWithStableIds.add(path);
            }

            double augmentDuration = (System.currentTimeMillis() - augmentStartTime) / 1000.0;
            MetricUtils.sendInternalRoutingStats(statsDClient, tags, augmentDuration, "augment_duration");

            if (pathsWithStableIds.size() == 0) {
                String message = "Transit path could not be found between " + fromPoint.getLat() + "," +
                        fromPoint.getLon() + " to " + toPoint.getLat() + "," + toPoint.getLon();
                // logger.warn(message);

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                tags = new String[]{"mode:pt", "api:grpc", "routes_found:false"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, 0);

                Status status = Status.newBuilder()
                        .setCode(Code.NOT_FOUND.getNumber())
                        .setMessage(message)
                        .build();
                responseObserver.onError(StatusProto.toStatusRuntimeException(status));
            } else {
                long replyBuildStart = System.currentTimeMillis();
                PtRouteReply.Builder replyBuilder = PtRouteReply.newBuilder();
                pathsWithStableIds.stream()
                        .map(RouterConverters::toPtPath)
                        .forEach(replyBuilder::addPaths);

                double replyBuildDuration = (System.currentTimeMillis() - replyBuildStart) / 1000.0;
                MetricUtils.sendInternalRoutingStats(statsDClient, tags, replyBuildDuration, "reply_build_duration");

                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                tags = new String[]{"mode:pt", "api:grpc", "routes_found:true"};
                tags = MetricUtils.applyCustomTags(tags, customTags);
                MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, pathsWithStableIds.size());

                // Request info log for slow-running requests; uncomment if needed for debugging
                /*
                if (durationSeconds > 30) {
                    logger.info("Slow request detected! Full request time: " + durationSeconds + "; internal routing time: "
                            + routeDuration + "; augment duration: " + augmentDuration + "; reply build duration: " + replyBuildDuration
                            + "; full request is " + request.toString());
                }
                */

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
            MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds, 0);

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
            MetricUtils.sendRoutingStats(statsDClient, tags, durationSeconds);

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
     * - adds stable edge ids to the walk and PT legs
     * - stores ACCESS/TRANSFER/EGRESS metadata on walk legs
     * - inserts empty walking ACCESS/TRANSFER/EGRESS legs, if they're missing
     *
     * @param path the ResponsePath to augment. modified in place
     */
    private void augmentLegsForPt(ResponsePath path, Request ghPtRequest) {
        // Replace the path's legs with newly-constructed legs containing stable edge IDs
        ArrayList<Trip.Leg> legs = new ArrayList<>(path.getLegs());
        path.getLegs().clear();

        boolean accessExists = false;
        boolean egressExists = false;
        Trip.Leg lastSeenLeg = null;
        for (int i = 0; i < legs.size(); i++) {
            Trip.Leg leg = legs.get(i);
            // Note: graphhopper returns Trip.WalkLegs even if we requested different access/egress modes
            if (leg instanceof Trip.WalkLeg) {
                Trip.WalkLeg thisLeg = (Trip.WalkLeg) leg;
                String travelSegmentType;
                String legMode = "foot";

                // Assign proper ACCESS/EGRESS/TRANSFER segment type based on position of walk leg in list
                if (i == 0) {
                    travelSegmentType = "ACCESS";
                    accessExists = true;
                    legMode = ghPtRequest.getAccessProfile();
                } else if (i == legs.size() - 1) {
                    travelSegmentType = "EGRESS";
                    egressExists = true;
                    legMode = ghPtRequest.getEgressProfile();
                } else {
                    // Note: transfer legs are always walking (mode "foot")
                    travelSegmentType = "TRANSFER";
                }
                path.getLegs().add(RouterConverters.toCustomStreetLeg(thisLeg, travelSegmentType, legMode));
            } else if (leg instanceof Trip.PtLeg) {
                Trip.PtLeg thisLeg = (Trip.PtLeg) leg;

                // If we haven't seen an ACCESS walk leg yet, or the last leg was a pt leg, insert an empty street leg
                if (i == 0){
                    path.getLegs().add(RouterConverters.createEmptyCustomStreetLeg(
                            ((LineString) leg.geometry).getPointN(0),
                            leg.getDepartureTime(), "ACCESS", ghPtRequest.getAccessProfile()
                    ));
                } else if (lastSeenLeg instanceof Trip.PtLeg) {
                    LineString lastSeenLegGeometry = (LineString) lastSeenLeg.geometry;
                    path.getLegs().add(RouterConverters.createEmptyCustomStreetLeg(
                            lastSeenLegGeometry.getPointN(lastSeenLegGeometry.getNumPoints() - 1),
                            leg.getDepartureTime(), "TRANSFER", "foot"
                    ));
                }

                long startTime = System.currentTimeMillis();
                CustomPtLeg customPtLeg = RouterConverters.toCustomPtLeg(thisLeg, gtfsFeedIdMapping, gtfsLinkMappings, gtfsRouteInfo);
                double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String[] tags = MetricUtils.applyCustomTags(new String[0], customTags);
                MetricUtils.sendInternalRoutingStats(statsDClient, tags, durationSeconds, "to_custom_pt_leg_seconds");

                path.getLegs().add(customPtLeg);
            }
            lastSeenLeg = leg;
        }

        // If no EGRESS walk leg exists, insert an empty one
        if (lastSeenLeg instanceof Trip.PtLeg) {
            LineString lastSeenLegGeometry = (LineString) lastSeenLeg.geometry;
            path.getLegs().add(RouterConverters.createEmptyCustomStreetLeg(
                    lastSeenLegGeometry.getPointN(lastSeenLegGeometry.getNumPoints() - 1),
                    lastSeenLeg.getArrivalTime(), "EGRESS", ghPtRequest.getEgressProfile()
            ));
        }

        // TODO: can we remove this clause? Or does the duplicate ID issue still exist for access/egress legs?
        if (accessExists && egressExists) {
            // ACCESS legs contains stable IDs for both ACCESS and EGRESS legs for some reason,
            // so we remove the EGRESS leg IDs from the ACCESS leg before storing the path
            CustomStreetLeg accessLeg = (CustomStreetLeg) path.getLegs().get(0);
            CustomStreetLeg egressLeg = (CustomStreetLeg) path.getLegs().get(path.getLegs().size() - 1);
            accessLeg.stableEdgeIds.removeAll(egressLeg.stableEdgeIds);
        }

        // Calculate correct distance incorporating foot + pt legs
        path.setDistance(path.getLegs().stream().mapToDouble(l -> l.distance).sum());

        path.getPathDetails().clear();
    }
}

package com.replica.api;

import com.conveyal.gtfs.model.Stop;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.gtfs.*;
import com.graphhopper.isochrone.algorithm.ContourBuilder;
import com.graphhopper.isochrone.algorithm.ReadableTriangulation;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.BBox;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.apache.commons.compress.utils.Lists;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.triangulate.ConformingDelaunayTriangulator;
import org.locationtech.jts.triangulate.ConstraintVertex;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;
import org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import router.RouterOuterClass;

import java.time.Instant;
import java.util.*;

public class TransitIsochroneRouter {

    private static final double JTS_TOLERANCE = 0.00001;
    private final GraphHopperGtfs graphHopper;

    public TransitIsochroneRouter(GraphHopperGtfs graphHopper) {
        this.graphHopper = graphHopper;
    }

    public void routePtIsochrone(RouterOuterClass.PtIsochroneRouteRequest request, StreamObserver<RouterOuterClass.IsochroneRouteReply> responseObserver) {
        Instant initialTime;
        try {
            initialTime = Instant.ofEpochSecond(request.getEarliestDepartureTime().getSeconds(), request.getEarliestDepartureTime().getNanos());
        } catch (Exception e) {
            String errorMessage = String.format("Illegal value for required parameter %s: [%s]", "earliest_departure_time", request.getEarliestDepartureTime().getSeconds());
            handleError(errorMessage, Code.INVALID_ARGUMENT, responseObserver);
            return;
        }

        GHLocation location = GHLocation.fromString(request.getCenter().getLat() + "," + request.getCenter().getLon());

        GeometryFactory geometryFactory = new GeometryFactory();
        EncodingManager encodingManager = graphHopper.getEncodingManager();
        BooleanEncodedValue accessEnc = encodingManager.getBooleanEncodedValue(VehicleAccess.key("foot"));
        DecimalEncodedValue speedEnc = encodingManager.getDecimalEncodedValue(VehicleSpeed.key("foot"));
        final Weighting weighting = new FastestWeighting(accessEnc, speedEnc);
        DefaultSnapFilter snapFilter = new DefaultSnapFilter(weighting, encodingManager.getBooleanEncodedValue(Subnetwork.key("foot")));

        GtfsStorage gtfsStorage = graphHopper.getGtfsStorage();
        boolean reverseFlow = request.getReverseFlow();
        PtLocationSnapper.Result snapResult;
        try {
            snapResult = new PtLocationSnapper(graphHopper.getBaseGraph(), graphHopper.getLocationIndex(), gtfsStorage).snapAll(Arrays.asList(location), Arrays.asList(snapFilter));
        } catch (PointNotFoundException e) {
            handleError(e.getMessage(), Code.NOT_FOUND, responseObserver);
            return;
        }
        GraphExplorer graphExplorer = new GraphExplorer(snapResult.queryGraph, gtfsStorage.getPtGraph(), weighting, gtfsStorage, RealtimeFeed.empty(), reverseFlow, false, false, 5.0, reverseFlow, request.getBlockedRouteTypes());
        MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, reverseFlow, false, false, 0, Collections.emptyList());

        NodeAccess nodeAccess = snapResult.queryGraph.getNodeAccess();
        Label.NodeId startingNode = snapResult.nodes.get(0);

        // Calculate target distance for each bucket
        double targetZ = request.getTimeLimit() * 1000;
        ArrayList<Double> bucketTargets = new ArrayList<>();
        OptionalInt nBuckets = OptionalInt.of(request.getNBuckets());
        double delta = targetZ / nBuckets.orElseThrow(() -> new IllegalArgumentException("query param buckets is not a number."));
        for (int i = 0; i < nBuckets.getAsInt(); i++) {
            bucketTargets.add((i + 1) * delta);
        }

        // Calculate isochrones for each bucket
        List<Map<Coordinate, Double>> pointsPerBucket = Lists.newArrayList();
        for (Double bucketTarget : bucketTargets) {
            pointsPerBucket.add(calcIsochrone(startingNode, nodeAccess, router, initialTime, reverseFlow, bucketTarget, gtfsStorage));
        }

        // Generate polygons for each bucket
        RouterOuterClass.IsochroneRouteReply.Builder replyBuilder = RouterOuterClass.IsochroneRouteReply.newBuilder();
        for (int i = 0; i < pointsPerBucket.size(); i++) {
            String isochronePolygon = getIsochronePolygon(request.getResultFormat().equals("multipoint"), geometryFactory,
                    pointsPerBucket.get(i), snapResult, nodeAccess, bucketTargets.get(i));
            replyBuilder.addBuckets(RouterOuterClass.IsochroneBucket.newBuilder()
                    .setBucket(i)
                    .setGeometry(isochronePolygon)
            );
        }

        responseObserver.onNext(replyBuilder.build());
        responseObserver.onCompleted();
    }

    private static void handleError(String errorMessage, Code code, StreamObserver<RouterOuterClass.IsochroneRouteReply> responseObserver) {
        Status status = Status.newBuilder()
                .setCode(code.getNumber())
                .setMessage(errorMessage)
                .build();
        responseObserver.onError(StatusProto.toStatusRuntimeException(status));
    }

    private Map<Coordinate, Double> calcIsochrone(Label.NodeId startingNode, NodeAccess nodeAccess, MultiCriteriaLabelSetting router,
                                               Instant initialTime, boolean reverseFlow, double targetZ, GtfsStorage gtfsStorage) {
        Map<Coordinate, Double> z1 = new HashMap<>();

        for (Label label : router.calcLabels(startingNode, initialTime)) {
            if (!((label.currentTime - initialTime.toEpochMilli()) * (reverseFlow ? -1 : 1) <= targetZ)) {
                break;
            }
            if (label.node.streetNode != -1) {
                Coordinate nodeCoordinate = new Coordinate(nodeAccess.getLon(label.node.streetNode), nodeAccess.getLat(label.node.streetNode));
                z1.merge(nodeCoordinate, (double) (label.currentTime - initialTime.toEpochMilli()) * (reverseFlow ? -1 : 1), Math::min);
            } else if (label.edge != null && (label.edge.getType() == GtfsStorage.EdgeType.EXIT_PT || label.edge.getType() == GtfsStorage.EdgeType.ENTER_PT)) {
                GtfsStorage.PlatformDescriptor platformDescriptor = label.edge.getPlatformDescriptor();
                Stop stop = gtfsStorage.getGtfsFeeds().get(platformDescriptor.feed_id).stops.get(platformDescriptor.stop_id);
                Coordinate nodeCoordinate = new Coordinate(stop.stop_lon, stop.stop_lat);
                z1.merge(nodeCoordinate, (double) (label.currentTime - initialTime.toEpochMilli()) * (reverseFlow ? -1 : 1), Math::min);
            }
        }
        return z1;
    }

    private String getIsochronePolygon(boolean multipoint, GeometryFactory geometryFactory, Map<Coordinate, Double> bucketPoints,
                                       PtLocationSnapper.Result snapResult, NodeAccess nodeAccess, double targetZ) {
        if (multipoint) {
            MultiPoint exploredPoints = geometryFactory.createMultiPointFromCoords(bucketPoints.keySet().toArray(new Coordinate[0]));
            return exploredPoints.toString();
        } else {
            MultiPoint exploredPoints = geometryFactory.createMultiPointFromCoords(bucketPoints.keySet().toArray(new Coordinate[0]));

            // Get at least all nodes within our bounding box (I think convex hull would be enough.)
            // I think then we should have all possible encroaching points. (Proof needed.)
            graphHopper.getLocationIndex().query(BBox.fromEnvelope(exploredPoints.getEnvelopeInternal()), edgeId -> {
                EdgeIteratorState edge = snapResult.queryGraph.getEdgeIteratorStateForKey(edgeId * 2);
                bucketPoints.merge(new Coordinate(nodeAccess.getLon(edge.getBaseNode()), nodeAccess.getLat(edge.getBaseNode())), Double.MAX_VALUE, Math::min);
                bucketPoints.merge(new Coordinate(nodeAccess.getLon(edge.getAdjNode()), nodeAccess.getLat(edge.getAdjNode())), Double.MAX_VALUE, Math::min);
            });
            exploredPoints = geometryFactory.createMultiPointFromCoords(bucketPoints.keySet().toArray(new Coordinate[0]));

            CoordinateList siteCoords = DelaunayTriangulationBuilder.extractUniqueCoordinates(exploredPoints);
            List<ConstraintVertex> constraintVertices = new ArrayList<>();
            for (Object siteCoord : siteCoords) {
                Coordinate coord = (Coordinate) siteCoord;
                constraintVertices.add(new ConstraintVertex(coord));
            }

            ConformingDelaunayTriangulator cdt = new ConformingDelaunayTriangulator(constraintVertices, JTS_TOLERANCE);
            cdt.setConstraints(new ArrayList(), new ArrayList());
            cdt.formInitialDelaunay();

            QuadEdgeSubdivision tin = cdt.getSubdivision();

            for (Vertex vertex : (Collection<Vertex>) tin.getVertices(true)) {
                if (tin.isFrameVertex(vertex)) {
                    vertex.setZ(Double.MAX_VALUE);
                } else {
                    Double aDouble = bucketPoints.get(vertex.getCoordinate());
                    if (aDouble != null) {
                        vertex.setZ(aDouble);
                    } else {
                        vertex.setZ(Double.MAX_VALUE);
                    }
                }
            }

            ReadableTriangulation triangulation = ReadableTriangulation.wrap(tin);
            ContourBuilder contourBuilder = new ContourBuilder(triangulation);
            MultiPolygon isoline = contourBuilder.computeIsoline(targetZ, triangulation.getEdges());
            return isoline.toString();
        }
    }
}

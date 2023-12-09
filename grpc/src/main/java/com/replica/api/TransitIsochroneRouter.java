package com.replica.api;

import com.conveyal.gtfs.model.Stop;
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
import com.graphhopper.util.shapes.BBox;
import io.grpc.stub.StreamObserver;
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
            throw new IllegalArgumentException(String.format("Illegal value for required parameter %s: [%s]", "earliest_departure_time", request.getEarliestDepartureTime().getSeconds()));
        }

        double targetZ = request.getTimeLimit() * 1000;
        GHLocation location = GHLocation.fromString(request.getCenter().getLat() + "," + request.getCenter().getLon());

        GeometryFactory geometryFactory = new GeometryFactory();
        EncodingManager encodingManager = graphHopper.getEncodingManager();
        BooleanEncodedValue accessEnc = encodingManager.getBooleanEncodedValue(VehicleAccess.key("foot"));
        DecimalEncodedValue speedEnc = encodingManager.getDecimalEncodedValue(VehicleSpeed.key("foot"));
        final Weighting weighting = new FastestWeighting(accessEnc, speedEnc);
        DefaultSnapFilter snapFilter = new DefaultSnapFilter(weighting, encodingManager.getBooleanEncodedValue(Subnetwork.key("foot")));

        GtfsStorage gtfsStorage = graphHopper.getGtfsStorage();
        boolean reverseFlow = request.getReverseFlow();
        PtLocationSnapper.Result snapResult = new PtLocationSnapper(graphHopper.getBaseGraph(), graphHopper.getLocationIndex(), gtfsStorage).snapAll(Arrays.asList(location), Arrays.asList(snapFilter));
        GraphExplorer graphExplorer = new GraphExplorer(snapResult.queryGraph, gtfsStorage.getPtGraph(), weighting, gtfsStorage, RealtimeFeed.empty(), reverseFlow, false, false, 5.0, reverseFlow, request.getBlockedRouteTypes());
        MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, reverseFlow, false, false, 0, Collections.emptyList());

        Map<Coordinate, Double> z1 = new HashMap<>();
        NodeAccess nodeAccess = snapResult.queryGraph.getNodeAccess();

        for (Label label : router.calcLabels(snapResult.nodes.get(0), initialTime)) {
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

        RouterOuterClass.IsochroneRouteReply.Builder replyBuilder = RouterOuterClass.IsochroneRouteReply.newBuilder();

        if (request.getResultFormat().equals("multipoint")) {
            MultiPoint exploredPoints = geometryFactory.createMultiPointFromCoords(z1.keySet().toArray(new Coordinate[0]));
            replyBuilder.addBuckets(RouterOuterClass.IsochroneBucket.newBuilder()
                    .setBucket(0)
                    .setGeometry(exploredPoints.toString())
            );
            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        } else {
            MultiPoint exploredPoints = geometryFactory.createMultiPointFromCoords(z1.keySet().toArray(new Coordinate[0]));

            // Get at least all nodes within our bounding box (I think convex hull would be enough.)
            // I think then we should have all possible encroaching points. (Proof needed.)
            graphHopper.getLocationIndex().query(BBox.fromEnvelope(exploredPoints.getEnvelopeInternal()), edgeId -> {
                EdgeIteratorState edge = snapResult.queryGraph.getEdgeIteratorStateForKey(edgeId * 2);
                z1.merge(new Coordinate(nodeAccess.getLon(edge.getBaseNode()), nodeAccess.getLat(edge.getBaseNode())), Double.MAX_VALUE, Math::min);
                z1.merge(new Coordinate(nodeAccess.getLon(edge.getAdjNode()), nodeAccess.getLat(edge.getAdjNode())), Double.MAX_VALUE, Math::min);
            });
            exploredPoints = geometryFactory.createMultiPointFromCoords(z1.keySet().toArray(new Coordinate[0]));

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
                    Double aDouble = z1.get(vertex.getCoordinate());
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
            replyBuilder.addBuckets(RouterOuterClass.IsochroneBucket.newBuilder()
                    .setBucket(0)
                    .setGeometry(isoline.toString())
            );
            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        }
    }
}

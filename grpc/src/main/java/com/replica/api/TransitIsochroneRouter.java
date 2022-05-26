package com.replica.api;

import com.graphhopper.GraphHopper;
import com.graphhopper.gtfs.*;
import com.graphhopper.isochrone.algorithm.ContourBuilder;
import com.graphhopper.isochrone.algorithm.ReadableTriangulation;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.shapes.BBox;
import io.grpc.stub.StreamObserver;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.triangulate.ConformingDelaunayTriangulator;
import org.locationtech.jts.triangulate.ConstraintVertex;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;
import org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

public class TransitIsochroneRouter {

    private static final double JTS_TOLERANCE = 0.00001;
    private final Function<Label, Double> z = label -> (double) label.currentTime;

    private static final Logger logger = LoggerFactory.getLogger(TransitIsochroneRouter.class);
    private final GraphHopper graphHopper;

    public TransitIsochroneRouter(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    public void routePtIsochrone(RouterOuterClass.PtIsochroneRouteRequest request, StreamObserver<RouterOuterClass.IsochroneRouteReply> responseObserver) {
        Instant initialTime;
        try {
            initialTime = Instant.ofEpochSecond(request.getEarliestDepartureTime().getSeconds(), request.getEarliestDepartureTime().getNanos());
        } catch (Exception e) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "Illegal value for required parameter %s: [%s]", "earliest_departure_time", request.getEarliestDepartureTime().getSeconds()));
        }

        double targetZ = initialTime.toEpochMilli() + request.getTimeLimit() * 1000;

        GeometryFactory geometryFactory = new GeometryFactory();
        final EdgeFilter filter = DefaultEdgeFilter.allEdges(graphHopper.getGraphHopperStorage().getEncodingManager().getEncoder("foot"));
        Snap snap = graphHopper.getLocationIndex().findClosest(request.getCenter().getLat(), request.getCenter().getLon(), filter);
        QueryGraph queryGraph = QueryGraph.create(graphHopper.getGraphHopperStorage(), Collections.singletonList(snap));
        if (!snap.isValid()) {
            throw new IllegalArgumentException("Cannot find point: " + request.getCenter());
        }

        PtEncodedValues ptEncodedValues = PtEncodedValues.fromEncodingManager(graphHopper.getEncodingManager());
        GtfsStorage gtfsStorage = ((GraphHopperGtfs) graphHopper).getGtfsStorage();
        GraphExplorer graphExplorer = new GraphExplorer(queryGraph, new FastestWeighting(graphHopper.getEncodingManager().getEncoder("foot")), ptEncodedValues, gtfsStorage, RealtimeFeed.empty(gtfsStorage), request.getReverseFlow(), false, 5.0, request.getReverseFlow());
        MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, ptEncodedValues, request.getReverseFlow(), false, false, false, 0, 1000000, Collections.emptyList());

        Map<Coordinate, Double> z1 = new HashMap<>();
        NodeAccess nodeAccess = queryGraph.getNodeAccess();

        MultiCriteriaLabelSetting.SPTVisitor sptVisitor = nodeLabel -> {
            Coordinate nodeCoordinate = new Coordinate(nodeAccess.getLongitude(nodeLabel.adjNode), nodeAccess.getLatitude(nodeLabel.adjNode));
            z1.merge(nodeCoordinate, this.z.apply(nodeLabel), Math::min);
        };

        RouterOuterClass.IsochroneRouteReply.Builder replyBuilder = RouterOuterClass.IsochroneRouteReply.newBuilder();

        if (request.getResultFormat().equals("multipoint")) {
            router.calcLabels(snap.getClosestNode(), initialTime, request.getBlockedRouteTypes(), sptVisitor, label -> label.currentTime <= targetZ);
            MultiPoint exploredPoints = geometryFactory.createMultiPointFromCoords(z1.keySet().toArray(new Coordinate[0]));
            replyBuilder.addBuckets(RouterOuterClass.IsochroneBucket.newBuilder()
                    .setBucket(0)
                    .setGeometry(exploredPoints.toString())
            );
            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        } else {
            router.calcLabels(snap.getClosestNode(), initialTime, request.getBlockedRouteTypes(), sptVisitor, label -> label.currentTime <= targetZ);
            MultiPoint exploredPoints = geometryFactory.createMultiPointFromCoords(z1.keySet().toArray(new Coordinate[0]));

            // Get at least all nodes within our bounding box (I think convex hull would be enough.)
            // I think then we should have all possible encroaching points. (Proof needed.)
            graphHopper.getLocationIndex().query(BBox.fromEnvelope(exploredPoints.getEnvelopeInternal()), new LocationIndex.Visitor() {
                @Override
                public void onNode(int nodeId) {
                    Coordinate nodeCoordinate = new Coordinate(nodeAccess.getLongitude(nodeId), nodeAccess.getLatitude(nodeId));
                    z1.merge(nodeCoordinate, Double.MAX_VALUE, Math::min);
                }
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

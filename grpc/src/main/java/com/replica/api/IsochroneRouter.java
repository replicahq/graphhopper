package com.replica.api;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.isochrone.algorithm.ContourBuilder;
import com.graphhopper.isochrone.algorithm.ShortestPathTree;
import com.graphhopper.isochrone.algorithm.Triangulator;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import io.grpc.stub.StreamObserver;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass;

import java.util.ArrayList;
import java.util.function.ToDoubleFunction;

import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;

public class IsochroneRouter {

    private static final Logger logger = LoggerFactory.getLogger(IsochroneRouter.class);
    private final GraphHopper graphHopper;
    private Triangulator isochroneTriangulator;

    public IsochroneRouter(GraphHopper graphHopper, Triangulator isochroneTriangulator) {
        this.graphHopper = graphHopper;
        this.isochroneTriangulator = isochroneTriangulator;
    }

    public void routeIsochrone(RouterOuterClass.IsochroneRouteRequest request, StreamObserver<RouterOuterClass.IsochroneRouteReply> responseObserver) {
        PMap hintsMap = new PMap();
        hintsMap.putObject(Parameters.CH.DISABLE, true);

        String mode = request.getMode();
        Profile profile = graphHopper.getProfile(mode);
        if (profile == null) {
            throw new IllegalArgumentException("The requested mode '" + mode + "' does not exist");
        }
        FlagEncoder encoder = graphHopper.getEncodingManager().getEncoder(profile.getVehicle());
        EdgeFilter edgeFilter = DefaultEdgeFilter.allEdges(encoder);
        LocationIndex locationIndex = graphHopper.getLocationIndex();
        Snap snap = locationIndex.findClosest(request.getCenter().getLat(), request.getCenter().getLon(), edgeFilter);
        if (!snap.isValid())
            throw new IllegalArgumentException("Point not found:" + request.getCenter());

        Graph graph = graphHopper.getGraphHopperStorage();
        QueryGraph queryGraph = QueryGraph.create(graph, snap);
        Weighting weighting = graphHopper.createWeighting(profile, hintsMap);
        TraversalMode traversalMode = profile.isTurnCosts() ? EDGE_BASED : NODE_BASED;
        ShortestPathTree shortestPathTree = new ShortestPathTree(queryGraph, weighting, request.getReverseFlow(), traversalMode);

        double limit;
        if (request.getWeightLimit() > 0) {
            limit = request.getWeightLimit();
            shortestPathTree.setWeightLimit(limit + Math.max(limit * 0.14, 2_000));
        } else if (request.getDistanceLimit() > 0) {
            limit = request.getDistanceLimit();
            shortestPathTree.setDistanceLimit(limit + Math.max(limit * 0.14, 2_000));
        } else {
            limit = request.getTimeLimit() * 1000;
            shortestPathTree.setTimeLimit(limit + Math.max(limit * 0.14, 200_000));
        }
        ArrayList<Double> zs = new ArrayList<>();
        double delta = limit / request.getNBuckets();
        for (int i = 0; i < request.getNBuckets(); i++) {
            zs.add((i + 1) * delta);
        }

        ToDoubleFunction<ShortestPathTree.IsoLabel> fz;
        if (request.getWeightLimit() > 0) {
            fz = l -> l.weight;
        } else if (request.getDistanceLimit() > 0) {
            fz = l -> l.distance;
        } else {
            fz = l -> l.time;
        }

        Triangulator.Result result = isochroneTriangulator.triangulate(snap, queryGraph, shortestPathTree, fz, degreesFromMeters(request.getTolerance()));

        ContourBuilder contourBuilder = new ContourBuilder(result.triangulation);
        ArrayList<Geometry> isochrones = new ArrayList<>();
        for (Double z : zs) {
            logger.info("Building contour z={}", z);
            MultiPolygon isochrone = contourBuilder.computeIsoline(z, result.seedEdges);
            if (!isochrone.isEmpty()) {
                if (request.getFullGeometry()) {
                    isochrones.add(isochrone);
                } else {
                    Polygon maxPolygon = heuristicallyFindMainConnectedComponent(
                            isochrone,
                            isochrone.getFactory().createPoint(
                                    new Coordinate(request.getCenter().getLon(), request.getCenter().getLat())
                            )
                    );
                    isochrones.add(isochrone.getFactory().createPolygon(((LinearRing) maxPolygon.getExteriorRing())));
                }
            }
        }
        RouterOuterClass.IsochroneRouteReply.Builder replyBuilder = RouterOuterClass.IsochroneRouteReply.newBuilder();
        for (int i = 0; i < isochrones.size(); i++) {
            Geometry isochrone = isochrones.get(i);
            replyBuilder.addBuckets(RouterOuterClass.IsochroneBucket.newBuilder()
                    .setBucket(i)
                    .setGeometry(isochrone.toString())
            );
        }
        responseObserver.onNext(replyBuilder.build());
        responseObserver.onCompleted();
    }

    // Copied from IsochroneResource.java
    private Polygon heuristicallyFindMainConnectedComponent(MultiPolygon multiPolygon, org.locationtech.jts.geom.Point point) {
        int maxPoints = 0;
        Polygon maxPolygon = null;
        for (int j = 0; j < multiPolygon.getNumGeometries(); j++) {
            Polygon polygon = (Polygon) multiPolygon.getGeometryN(j);
            if (polygon.contains(point)) {
                return polygon;
            }
            if (polygon.getNumPoints() > maxPoints) {
                maxPoints = polygon.getNumPoints();
                maxPolygon = polygon;
            }
        }
        return maxPolygon;
    }

    /**
     * We want to specify a tolerance in something like meters, but we need it in unprojected lat/lon-space.
     * This is more correct in some parts of the world, and in some directions, than in others.
     *
     * Copied from IsochroneResource.java
     *
     * @param distanceInMeters distance in meters
     * @return "distance" in degrees
     */
    static double degreesFromMeters(double distanceInMeters) {
        return distanceInMeters / DistanceCalcEarth.METERS_PER_DEGREE;
    }
}

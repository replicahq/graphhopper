package com.replica.api;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.isochrone.algorithm.ContourBuilder;
import com.graphhopper.isochrone.algorithm.ShortestPathTree;
import com.graphhopper.isochrone.algorithm.Triangulator;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
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
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.ToDoubleFunction;

import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;

public class IsochroneRouter {

    private static final Logger logger = LoggerFactory.getLogger(IsochroneRouter.class);
    private final GraphHopper graphHopper;
    private Triangulator triangulator;

    public IsochroneRouter(GraphHopper graphHopper, Triangulator triangulator) {
        this.graphHopper = graphHopper;
        this.triangulator = triangulator;
    }

    public void routeIsochrone(RouterOuterClass.IsochroneRouteRequest request, StreamObserver<RouterOuterClass.IsochroneRouteReply> responseObserver) {
        PMap hintsMap = new PMap();
        hintsMap.putObject(Parameters.CH.DISABLE, true);
        hintsMap.putObject(Parameters.Landmark.DISABLE, true);

        String profileName = request.getMode();
        Profile profile = graphHopper.getProfile(profileName);
        if (profile == null)
            throw new IllegalArgumentException("The requested profile '" + profileName + "' does not exist");
        LocationIndex locationIndex = graphHopper.getLocationIndex();
        BaseGraph graph = graphHopper.getBaseGraph();
        Weighting weighting = graphHopper.createWeighting(profile, hintsMap);
        BooleanEncodedValue inSubnetworkEnc = graphHopper.getEncodingManager().getBooleanEncodedValue(Subnetwork.key(profileName));
        Snap snap = locationIndex.findClosest(request.getCenter().getLat(), request.getCenter().getLon(), new DefaultSnapFilter(weighting, inSubnetworkEnc));
        if (!snap.isValid())
            throw new IllegalArgumentException("Point not found: " + request.getCenter().getLat() + ", " + request.getCenter().getLon());
        QueryGraph queryGraph = QueryGraph.create(graph, snap);
        TraversalMode traversalMode = profile.isTurnCosts() ? EDGE_BASED : NODE_BASED;
        ShortestPathTree shortestPathTree = new ShortestPathTree(queryGraph, queryGraph.wrapWeighting(weighting), request.getReverseFlow(), traversalMode);

        double limit;
        ToDoubleFunction<ShortestPathTree.IsoLabel> fz;
        OptionalLong weightLimit = OptionalLong.of(request.getWeightLimit());
        OptionalLong distanceLimitInMeter = OptionalLong.of(request.getDistanceLimit());
        OptionalLong timeLimitInSeconds = OptionalLong.of(request.getTimeLimit());
        OptionalInt nBuckets = OptionalInt.of(request.getNBuckets());

        if (weightLimit.orElseThrow(() -> new IllegalArgumentException("query param weight_limit is not a number.")) > 0) {
            limit = weightLimit.getAsLong();
            shortestPathTree.setWeightLimit(limit + Math.max(limit * 0.14, 200));
            fz = l -> l.weight;
        } else if (distanceLimitInMeter.orElseThrow(() -> new IllegalArgumentException("query param distance_limit is not a number.")) > 0) {
            limit = distanceLimitInMeter.getAsLong();
            shortestPathTree.setDistanceLimit(limit + Math.max(limit * 0.14, 2_000));
            fz = l -> l.distance;
        } else {
            limit = timeLimitInSeconds.orElseThrow(() -> new IllegalArgumentException("query param time_limit is not a number.")) * 1000d;
            shortestPathTree.setTimeLimit(limit + Math.max(limit * 0.14, 200_000));
            fz = l -> l.time;
        }
        ArrayList<Double> zs = new ArrayList<>();
        double delta = limit / nBuckets.orElseThrow(() -> new IllegalArgumentException("query param buckets is not a number."));
        for (int i = 0; i < nBuckets.getAsInt(); i++) {
            zs.add((i + 1) * delta);
        }

        Triangulator.Result result = triangulator.triangulate(snap, queryGraph, shortestPathTree, fz, degreesFromMeters(request.getTolerance()));

        ContourBuilder contourBuilder = new ContourBuilder(result.triangulation);
        ArrayList<Geometry> isochrones = new ArrayList<>();
        for (Double z : zs) {
            logger.info("Building contour z={}", z);
            MultiPolygon isochrone = contourBuilder.computeIsoline(z, result.seedEdges);
            if (request.getFullGeometry()) {
                isochrones.add(isochrone);
            } else {
                Polygon maxPolygon = heuristicallyFindMainConnectedComponent(isochrone, isochrone.getFactory().createPoint(new Coordinate(request.getCenter().getLon(), request.getCenter().getLat())));
                isochrones.add(isochrone.getFactory().createPolygon(((LinearRing) maxPolygon.getExteriorRing())));
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
    private Polygon heuristicallyFindMainConnectedComponent(MultiPolygon multiPolygon, Point point) {
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
     * @param distanceInMeters distance in meters
     * @return "distance" in degrees
     */
    static double degreesFromMeters(double distanceInMeters) {
        return distanceInMeters / DistanceCalcEarth.METERS_PER_DEGREE;
    }
}

package com.graphhopper.stableid;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Longs;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.*;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Map;


public class StableIdEncodedValues {

    private IntEncodedValue[] stableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue[] reverseStableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue osmWayIdEnc;
    private OsmHelper osmHelper;
    private EnumEncodedValue<RoadClass> roadClassEnc;

    private final int QUADRANT_SIZE = 1;  // size (in degrees) of each quandrant used to generalize bearing
    private final long MAX_QUADRANT_INDEX = (360 / QUADRANT_SIZE) - 1;
    private final int DISTANCE_BUCKET_SIZE = 2;  // size (in meters) of each "distance bucket" used to generalize distance

    private StableIdEncodedValues(EncodingManager encodingManager, OsmHelper osmHelper) {
        this.osmHelper = osmHelper;
        this.osmWayIdEnc = encodingManager.getIntEncodedValue("osmid");
        roadClassEnc = encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);

        for (int i=0; i<8; i++) {
            stableIdEnc[i] = encodingManager.getIntEncodedValue("stable_id_byte_"+i);
        }
        for (int i=0; i<8; i++) {
            reverseStableIdEnc[i] = encodingManager.getIntEncodedValue("reverse_stable_id_byte_"+i);
        }
    }

    public static StableIdEncodedValues fromEncodingManager(EncodingManager encodingManager, OsmHelper osmHelper) {
        return new StableIdEncodedValues(encodingManager, osmHelper);
    }

    // Used only for instances where stable edge IDs are being accessed (not set)
    // ie, StableIdPathDetailsBuilder
    public static StableIdEncodedValues fromEncodingManager(EncodingManager encodingManager) {
        return new StableIdEncodedValues(encodingManager, null);
    }

    public final String getStableId(boolean reverse, EdgeIteratorState edge) {
        byte[] stableId = new byte[8];
        IntEncodedValue[] idByte = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            stableId[i] = (byte) edge.get(idByte[i]);
        }
        return Long.toUnsignedString(Longs.fromByteArray(stableId));
    }

    public final void setStableId(boolean reverse, EdgeIteratorState edge, Map<Long, String> osmIdToHighway) {
        int startVertex = edge.getBaseNode();
        int endVertex = edge.getAdjNode();

        long startOsmNodeId = reverse ? osmHelper.getOSMNode(endVertex) : osmHelper.getOSMNode(startVertex);
        long endOsmNodeId = reverse ? osmHelper.getOSMNode(startVertex) : osmHelper.getOSMNode(endVertex);

        // Check if start or end node IDs are artificial IDs; if so, replace them with real IDs
        if (startOsmNodeId <= 0) {
            startOsmNodeId = osmHelper.getRealNodeIdFromArtificial(startOsmNodeId);
        }
        if (endOsmNodeId <= 0) {
            endOsmNodeId = osmHelper.getRealNodeIdFromArtificial(endOsmNodeId);
        }

        long osmWayId = edge.get(osmWayIdEnc);

        PointList points = edge.fetchWayGeometry(FetchMode.ALL);

        double startLat = points.getLat(0);
        double startLon = points.getLon(0);
        double endBearingLat, endBearingLon, endLat, endLon;
        if (points.size() <= 1) {
            return;
        } else {
            endBearingLat = points.getLat(1);
            endBearingLon = points.getLon(1);
            endLat = points.getLat(points.size() - 1);
            endLon = points.getLon(points.size() - 1);
        }

        // String pointsString = String.format("%.5f %.5f %.5f %.5f", startLat, startLon, endLat, endLon);

        DecimalFormat df = new DecimalFormat("#.#####");
        df.setRoundingMode(RoundingMode.CEILING);
        String pointsString = String.format("%s %s %s %s", df.format(startLat), df.format(startLon), df.format(endLat), df.format(endLon));

        long distanceMeters = Math.round(DistanceCalcEarth.DIST_EARTH.calcDist(startLat, startLon, endLat, endLon));
        long distanceBucket = distanceMeters / DISTANCE_BUCKET_SIZE;

        long bearing = Math.round(AngleCalc.ANGLE_CALC.calcAzimuth(startLat, startLon, endBearingLat, endBearingLon));
        // outputs which "quadrant" the line between start + end point falls in, between 0 and 3
        long quadrant = (bearing % 360) / QUADRANT_SIZE;

        // Ensure quadrant makes sense
        if (quadrant < 0L || quadrant > MAX_QUADRANT_INDEX) {
            throw new RuntimeException("Quadrant for edge is outside of expected bounds of [0,23]!");
        }

        // Ensure OSM node + way IDs are set for every edge
        if (startOsmNodeId <= 0L || endOsmNodeId <= 0L || osmWayId <= 0L) {
            throw new RuntimeException("Trying to set stable edge ID on edge with no OSM node or way IDs stored!");
        }

        String highwayTag = osmIdToHighway.getOrDefault(osmWayId, edge.get(roadClassEnc).toString());

        byte[] stableId = calculateStableEdgeId(pointsString, distanceBucket, quadrant, highwayTag, reverse, osmWayId);
        if (stableId.length != 8)
            throw new IllegalArgumentException("stable ID must be 8 bytes: " + new String(stableId));

        IntEncodedValue[] idBytes = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            edge.set(idBytes[i], Byte.toUnsignedInt(stableId[i]));
        }
    }

    private static byte[] calculateStableEdgeId(/*long startOsmNodeId, long endOsmNodeId, long osmWayId,*/  String pointsString, long distanceBucket, long quadrant, String highwayTag, boolean reverse, long osmWayId) {
        String reverseSuffix = reverse ? "-" : "+";
        // String hashString = String.format("%d %d %d %d %s", startOsmNodeId, endOsmNodeId, osmWayId, quadrant, reverseSuffix); // todo: remember why I need reverseSuffix in there
        String hashString = String.format("%s %d %d %s %s", pointsString, distanceBucket, quadrant, highwayTag, reverseSuffix);

        HashCode hc = Hashing.farmHashFingerprint64().hashString(hashString, Charsets.UTF_8);
        return hc.asBytes();
    }

    public static String calculateSegmentId(long osmWayId, int segmentIndex, boolean reverse) {
        String reverseSuffix = reverse ? "-" : "+";
        // We store 1-indexed segments because 0 is a default value for "unset",
        // so 0 is used to sanity-check whether or not a segment index has
        // been found for every edge. But, we want to output 0-indexed segments for
        // human-readable IDs, so we bump the index down by 1 here before outputting them
        segmentIndex--;
        return String.format("%d_%d%s", osmWayId, segmentIndex, reverseSuffix);
    }
}

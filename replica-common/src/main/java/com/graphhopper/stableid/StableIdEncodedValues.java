package com.graphhopper.stableid;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Longs;
import com.graphhopper.GraphHopper;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.AngleCalc;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;


public class StableIdEncodedValues {

    private IntEncodedValue[] stableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue[] reverseStableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue osmWayIdEnc;
    private OsmHelper osmHelper;

    private StableIdEncodedValues(EncodingManager encodingManager, OsmHelper osmHelper) {
        this.osmHelper = osmHelper;
        this.osmWayIdEnc = encodingManager.getIntEncodedValue("osmid");

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

    public final void setStableId(boolean reverse, EdgeIteratorState edge, GraphHopper graphhopper) {
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
        double endLat, endLon;
        if (points.size() <= 1) {
            return;
        } else {
            endLat = points.getLat(1);
            endLon = points.getLon(1);
        }

        long bearing = Math.round(AngleCalc.ANGLE_CALC.calcAzimuth(startLat, startLon, endLat, endLon));
        // outputs which "quadrant" the line between start + end point falls in, between 0 and 3
        long quadrant = (bearing % 360) / 15;

        // Ensure quadrant makes sense
        if (quadrant < 0L || quadrant > 23L) {
            throw new RuntimeException("Quadrant for edge is outside of expected bounds of [0,23]!");
        }

        // Ensure OSM node + way IDs are set for every edge
        if (startOsmNodeId <= 0L || endOsmNodeId <= 0L || osmWayId <= 0L) {
            throw new RuntimeException("Trying to set stable edge ID on edge with no OSM node or way IDs stored!");
        }

        byte[] stableId = calculateStableEdgeId(startOsmNodeId, endOsmNodeId, osmWayId, quadrant, reverse);
        if (stableId.length != 8)
            throw new IllegalArgumentException("stable ID must be 8 bytes: " + new String(stableId));

        IntEncodedValue[] idBytes = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            edge.set(idBytes[i], Byte.toUnsignedInt(stableId[i]));
        }
    }

    private static byte[] calculateStableEdgeId(long startOsmNodeId, long endOsmNodeId, long osmWayId, long quadrant, boolean reverse) {
        String reverseSuffix = reverse ? "-" : "+";
        String hashString = String.format("%d %d %d %d %s", startOsmNodeId, endOsmNodeId, osmWayId, quadrant, reverseSuffix);

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

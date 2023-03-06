package com.graphhopper.stableid;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Longs;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.AngleCalc;
import com.graphhopper.util.EdgeIteratorState;


public class StableIdEncodedValues {

    private IntEncodedValue[] stableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue[] reverseStableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue osmWayIdEnc;
    private OsmHelper osmHelper;
    private NodeAccess nodes;

    private StableIdEncodedValues(EncodingManager encodingManager, OsmHelper osmHelper, NodeAccess nodes) {
        this.osmHelper = osmHelper;
        this.osmWayIdEnc = encodingManager.getIntEncodedValue("osmid");

        for (int i=0; i<8; i++) {
            stableIdEnc[i] = encodingManager.getIntEncodedValue("stable_id_byte_"+i);
        }
        for (int i=0; i<8; i++) {
            reverseStableIdEnc[i] = encodingManager.getIntEncodedValue("reverse_stable_id_byte_"+i);
        }
        this.nodes = nodes;
    }

    public static StableIdEncodedValues fromEncodingManager(EncodingManager encodingManager, OsmHelper osmHelper, NodeAccess nodes) {
        return new StableIdEncodedValues(encodingManager, osmHelper, nodes);
    }

    // Used only for instances where stable edge IDs are being accessed (not set)
    // ie, StableIdPathDetailsBuilder
    public static StableIdEncodedValues fromEncodingManager(EncodingManager encodingManager, NodeAccess nodes) {
        return new StableIdEncodedValues(encodingManager, null, nodes);
    }

    public final String getStableId(boolean reverse, EdgeIteratorState edge) {
        byte[] stableId = new byte[8];
        IntEncodedValue[] idByte = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            stableId[i] = (byte) edge.get(idByte[i]);
        }
        return Long.toUnsignedString(Longs.fromByteArray(stableId));
    }

    public final void setStableId(boolean reverse, EdgeIteratorState edge) {
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

        double startLat = nodes.getLat(startVertex);
        double startLon = nodes.getLon(startVertex);
        double endLat = nodes.getLat(endVertex);
        double endLon = nodes.getLon(endVertex);
        long bearing = Math.round(AngleCalc.ANGLE_CALC.calcAzimuth(startLat, startLon, endLat, endLon));
        long quadrant = bearing / 90;

        // Ensure OSM node + way IDs are set for every edge
        if (startOsmNodeId <= 0L || endOsmNodeId <= 0L || osmWayId <= 0L) {
            throw new RuntimeException("Trying to set stable edge ID on edge with no OSM node or way IDs stored!");
        }

        byte[] stableId = calculateStableEdgeId(startOsmNodeId, endOsmNodeId, osmWayId, quadrant);
        if (stableId.length != 8)
            throw new IllegalArgumentException("stable ID must be 8 bytes: " + new String(stableId));

        IntEncodedValue[] idBytes = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            edge.set(idBytes[i], Byte.toUnsignedInt(stableId[i]));
        }
    }

    private static byte[] calculateStableEdgeId(long startOsmNodeId, long endOsmNodeId, long osmWayId, long quadrant) {
        String hashString = String.format("%d %d %d %d", startOsmNodeId, endOsmNodeId, osmWayId, quadrant);

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

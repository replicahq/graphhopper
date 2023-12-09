package com.graphhopper.stableid;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Longs;
import com.graphhopper.OsmHelper;
import com.graphhopper.RouterConstants;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.EdgeIteratorState;

public class StableIdEncodedValues {

    private IntEncodedValue[] stableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue[] reverseStableIdEnc = new IntEncodedValue[8];
    private IntEncodedValue osmWayIdEnc;
    private OsmHelper osmHelper;

    private StableIdEncodedValues(EncodingManager encodingManager, OsmHelper osmHelper) {
        this.osmHelper = osmHelper;
        this.osmWayIdEnc = encodingManager.getIntEncodedValue(RouterConstants.OSM_ID_ENCODED_VALUE);

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

    public final void setStableId(boolean reverse, EdgeIteratorState edge) {
        long osmWayId = edge.get(osmWayIdEnc);
        int segmentIndex = osmHelper.getSegmentIndexForGhEdge(edge.getEdge());

        // Ensure segment index is set for every edge
        if (segmentIndex <= 0L) {
            throw new RuntimeException("Trying to set stable edge ID on edge with no segment index stored!");
        }

        byte[] stableId = calculateStableEdgeId(osmWayId, segmentIndex, reverse);
        if (stableId.length != 8)
            throw new IllegalArgumentException("stable ID must be 8 bytes: " + new String(stableId));

        IntEncodedValue[] idBytes = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            edge.set(idBytes[i], Byte.toUnsignedInt(stableId[i]));
        }
    }

    private static byte[] calculateStableEdgeId(long osmWayId, int segmentIndex, boolean reverse) {
        String hashString = calculateHumanReadableStableEdgeId(osmWayId, segmentIndex, reverse);
        HashCode hc = Hashing.farmHashFingerprint64().hashString(hashString, Charsets.UTF_8);
        return hc.asBytes();
    }

    public static String calculateHumanReadableStableEdgeId(long osmWayId, int segmentIndex, boolean reverse) {
        String reverseSuffix = reverse ? "-" : "+";
        // We store 1-indexed segments because 0 is a default value for "unset",
        // so 0 is used to sanity-check whether or not a segment index has
        // been found for every edge. But, we want to output 0-indexed segments for
        // human-readable IDs, so we bump the index down by 1 here before outputting them
        segmentIndex--;
        return String.format("%d_%d%s", osmWayId, segmentIndex, reverseSuffix);
    }
}

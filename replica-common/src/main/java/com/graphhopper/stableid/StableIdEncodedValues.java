package com.graphhopper.stableid;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Longs;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.ev.UnsignedIntEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

public class StableIdEncodedValues {

    private UnsignedIntEncodedValue[] stableIdEnc = new UnsignedIntEncodedValue[8];
    private UnsignedIntEncodedValue[] reverseStableIdEnc = new UnsignedIntEncodedValue[8];
    private OsmHelper osmHelper;

    private StableIdEncodedValues(EncodingManager encodingManager, OsmHelper osmHelper) {
        this.osmHelper = osmHelper;
        for (int i=0; i<8; i++) {
            stableIdEnc[i] = (UnsignedIntEncodedValue) encodingManager.getIntEncodedValue("stable-id-byte-"+i);
        }
        for (int i=0; i<8; i++) {
            reverseStableIdEnc[i] = (UnsignedIntEncodedValue) encodingManager.getIntEncodedValue("reverse-stable-id-byte-"+i);
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

    public static void createAndAddEncodedValues(EncodingManager.Builder emBuilder) {
        for (int i=0; i<8; i++) {
            emBuilder.add(new UnsignedIntEncodedValue("stable-id-byte-"+i, 8, false));
        }
        for (int i=0; i<8; i++) {
            emBuilder.add(new UnsignedIntEncodedValue("reverse-stable-id-byte-"+i, 8, false));
        }
    }

    public final String getStableId(boolean reverse, EdgeIteratorState edge) {
        byte[] stableId = new byte[8];
        UnsignedIntEncodedValue[] idByte = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            stableId[i] = (byte) edge.get(idByte[i]);
        }
        return Long.toUnsignedString(Longs.fromByteArray(stableId));
    }

    public final void setStableId(boolean reverse, EdgeIteratorState edge) {
        int ghEdgeId = edge.getEdge();

        long startOsmNodeId = reverse ? osmHelper.getOSMNode(osmHelper.getNodeAdjacentToEdge(ghEdgeId)) :
                osmHelper.getOSMNode(osmHelper.getBaseNodeForEdge(ghEdgeId));
        long endOsmNodeId = reverse ? osmHelper.getOSMNode(osmHelper.getBaseNodeForEdge(ghEdgeId)) :
                osmHelper.getOSMNode(osmHelper.getNodeAdjacentToEdge(ghEdgeId));

        long osmWayId = osmHelper.getOSMWay(ghEdgeId);

        PointList wayGeometry = edge.fetchWayGeometry(FetchMode.ALL);
        String geometryString = wayGeometry.toLineString(false).toString();
        if (reverse) {
            wayGeometry.reverse();
            geometryString = wayGeometry.toLineString(false).toString();
        }

        // Only set stable edge IDs for edges with complete OSM info stored (ie, the edges we export)
        if (startOsmNodeId == 0L || endOsmNodeId == 0L || osmWayId == -1L) {
            return;
        }

        byte[] stableId = calculateStableEdgeId(startOsmNodeId, endOsmNodeId, osmWayId, geometryString);
        if (stableId.length != 8)
            throw new IllegalArgumentException("stable ID must be 8 bytes: " + new String(stableId));

        UnsignedIntEncodedValue[] idBytes = reverse ? reverseStableIdEnc : stableIdEnc;
        for (int i=0; i<8; i++) {
            edge.set(idBytes[i], Byte.toUnsignedInt(stableId[i]));
        }
    }

    private static byte[] calculateStableEdgeId(long startOsmNodeId, long endOsmNodeId, long osmWayId, String geometryString) {
        String hashString = String.format("%d %d %d %s", startOsmNodeId, endOsmNodeId, osmWayId, geometryString);

        HashCode hc = Hashing.farmHashFingerprint64().hashString(hashString, Charsets.UTF_8);
        return hc.asBytes();
    }
}

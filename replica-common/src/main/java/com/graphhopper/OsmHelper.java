package com.graphhopper;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.util.BitUtil;

import java.util.Map;

public class OsmHelper {
    private DataAccess edgeMapping;
    private DataAccess nodeMapping;
    private DataAccess edgeAdjacentMapping;
    private DataAccess edgeBaseMapping;
    private BitUtil bitUtil;
    private long edgeCount;

    public OsmHelper(DataAccess edgeMapping, DataAccess nodeMapping,
                     DataAccess edgeAdjacentMapping, DataAccess edgeBaseMapping,
                     BitUtil bitUtil, long edgeCount) {
        this.edgeMapping = edgeMapping;
        this.nodeMapping = nodeMapping;
        this.edgeAdjacentMapping = edgeAdjacentMapping;
        this.edgeBaseMapping = edgeBaseMapping;
        this.bitUtil = bitUtil;
        this.edgeCount = edgeCount;
    }

    /*
    public long getOSMWay(int internalEdgeId) {
        if (internalEdgeId >= edgeCount) {
            return -1;
        }
        long pointer = 8L * internalEdgeId;
        return bitUtil.combineIntsToLong(edgeMapping.getInt(pointer), edgeMapping.getInt(pointer + 4L));
    }

    public long getNodeAdjacentToEdge(int edgeId) {
        long pointer = 8L * edgeId;
        return bitUtil.combineIntsToLong(edgeAdjacentMapping.getInt(pointer), edgeAdjacentMapping.getInt(pointer + 4L));
    }

    public long getBaseNodeForEdge(int edgeId) {
        long pointer = 8L * edgeId;
        return bitUtil.combineIntsToLong(edgeBaseMapping.getInt(pointer), edgeBaseMapping.getInt(pointer + 4L));
    }
    */

    public long getOSMNode(long internalNodeId) {
        long pointer = 8L * internalNodeId;
        return bitUtil.combineIntsToLong(nodeMapping.getInt(pointer), nodeMapping.getInt(pointer + 4L));
    }

    public static Map<String, String> getLanesTag(long osmId, Map<Long, Map<String, String>> osmIdToLaneTags) {
        return osmIdToLaneTags.getOrDefault(osmId, null);
    }
}

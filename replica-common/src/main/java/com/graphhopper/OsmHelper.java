package com.graphhopper;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.util.BitUtil;

import java.util.Map;

public class OsmHelper {
    private DataAccess edgeMapping;
    private DataAccess nodeMapping;
    private BitUtil bitUtil;
    private long nodeCount;
    private long edgeCount;

    public OsmHelper(DataAccess edgeMapping, DataAccess nodeMapping, BitUtil bitUtil,
                     long nodeCount, long edgeCount) {
        System.out.println("nodeCount: " + nodeCount);
        this.edgeMapping = edgeMapping;
        this.nodeMapping = nodeMapping;
        this.bitUtil = bitUtil;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
    }

    public long getOSMWay(int internalEdgeId) {
        if (internalEdgeId >= edgeCount) {
            return -1;
        }
        long pointer = 8L * internalEdgeId;
        return bitUtil.combineIntsToLong(edgeMapping.getInt(pointer), edgeMapping.getInt(pointer + 4L));
    }

    public long getOSMNode(int internalNodeId) {
        if (internalNodeId >= nodeCount) {
            return -1;
        }
        long pointer = 8L * internalNodeId;
        System.out.println("get pointer: " + pointer);
        if (nodeMapping.getInt(pointer) == 0 || nodeMapping.getInt(pointer + 4L) == 0) {
            System.out.println("intlow: " + nodeMapping.getInt(pointer) + " ; inthigh "+ nodeMapping.getInt(pointer + 4L) + "; internal ID: " + internalNodeId);
        }
        long ret = bitUtil.combineIntsToLong(nodeMapping.getInt(pointer), nodeMapping.getInt(pointer + 4L));
        System.out.println("ret is: " + ret);
        return ret;
    }

    public static Map<String, String> getLanesTag(long osmId, Map<Long, Map<String, String>> osmIdToLaneTags) {
        return osmIdToLaneTags.getOrDefault(osmId, null);
    }
}

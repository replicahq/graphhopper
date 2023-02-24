package com.graphhopper;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.util.BitUtil;

import java.util.Map;

public class OsmHelper {
    private DataAccess nodeMapping;
    private DataAccess artificialIdToOsmNodeIdMapping;
    private DataAccess ghEdgeIdToSegmentIndexMapping;
    private BitUtil bitUtil;

    public OsmHelper(DataAccess nodeMapping,
                     DataAccess artificialIdToOsmNodeIdMapping,
                     DataAccess ghEdgeIdToSegmentIndexMapping,
                     BitUtil bitUtil) {
        this.nodeMapping = nodeMapping;
        this.artificialIdToOsmNodeIdMapping = artificialIdToOsmNodeIdMapping;
        this.ghEdgeIdToSegmentIndexMapping = ghEdgeIdToSegmentIndexMapping;
        this.bitUtil = bitUtil;
    }

    public long getRealNodeIdFromArtificial(long artificialNodeId) {
        long pointer = 8L * artificialNodeId;
        return bitUtil.combineIntsToLong(artificialIdToOsmNodeIdMapping.getInt(pointer), artificialIdToOsmNodeIdMapping.getInt(pointer + 4L));
    }

    public long getOSMNode(long internalNodeId) {
        long pointer = 8L * internalNodeId;
        return bitUtil.combineIntsToLong(nodeMapping.getInt(pointer), nodeMapping.getInt(pointer + 4L));
    }

    public int getSegmentIndexForGhEdge(int ghEdgeId) {
        long pointer = 4L * ghEdgeId;
        return ghEdgeIdToSegmentIndexMapping.getInt(pointer);
    }

    public static Map<String, String> getLanesTag(long osmId, Map<Long, Map<String, String>> osmIdToLaneTags) {
        return osmIdToLaneTags.getOrDefault(osmId, null);
    }
}

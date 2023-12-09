package com.graphhopper;

import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderWay;
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
        return bitUtil.toLong(artificialIdToOsmNodeIdMapping.getInt(pointer), artificialIdToOsmNodeIdMapping.getInt(pointer + 4L));
    }

    public long getOSMNode(long internalNodeId) {
        long pointer = 8L * internalNodeId;
        return bitUtil.toLong(nodeMapping.getInt(pointer), nodeMapping.getInt(pointer + 4L));
    }

    public int getSegmentIndexForGhEdge(int ghEdgeId) {
        long pointer = 4L * ghEdgeId;
        return ghEdgeIdToSegmentIndexMapping.getInt(pointer);
    }

    public static Map<String, String> getLanesTag(long osmId, Map<Long, Map<String, String>> osmIdToLaneTags) {
        return osmIdToLaneTags.getOrDefault(osmId, null);
    }

    public static String getHighwayFromOsmWay(ReaderWay way) {
        if (way.hasTag("highway")) {
            return way.getTag("highway");
        } else {
            return null;
        }
    }

    // if only `name` or only `ref` tag exist, return that. if both exist, return "<ref>, <name>". else, return null
    public static String getConcatNameFromOsmElement(ReaderElement wayOrRelation) {
        String name = null;
        if (wayOrRelation.hasTag("name")) {
            name = wayOrRelation.getTag("name");
        }
        if (wayOrRelation.hasTag("ref")) {
            name = name == null ? wayOrRelation.getTag("ref") : wayOrRelation.getTag("ref") + ", " + name;
        }
        return name;
    }
}

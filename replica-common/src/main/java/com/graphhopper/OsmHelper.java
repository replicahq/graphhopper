package com.graphhopper;

import com.google.common.collect.Sets;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.util.BitUtil;

import java.util.Map;
import java.util.Set;

public class OsmHelper {
    private DataAccess nodeMapping;
    private DataAccess artificialIdToOsmNodeIdMapping;
    private DataAccess ghEdgeIdToSegmentIndexMapping;
    private BitUtil bitUtil;

    public static final String OSM_NAME_TAG = "name";
    public static final String OSM_HIGHWAY_TAG = "highway";
    public static final String OSM_DIRECTION_TAG = "direction";
    public static final String OSM_LANES_TAG = "lanes";
    public static final String OSM_FORWARD_LANES_TAG = "lanes:forward";
    public static final String OSM_BACKWARD_LANES_TAG = "lanes:backward";

    // Tags we consider when calculating the value of the `lanes` column
    public static final Set<String> LANE_TAGS = Sets.newHashSet(OSM_LANES_TAG, OSM_FORWARD_LANES_TAG, OSM_BACKWARD_LANES_TAG);
    // Tags we parse to include as columns in network link export
    public static final Set<String> WAY_TAGS = Sets.newHashSet(OSM_HIGHWAY_TAG, OSM_DIRECTION_TAG);
    public static final Set<String> ALL_TAGS_TO_PARSE = Sets.union(LANE_TAGS, WAY_TAGS);

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

    public static Map<String, String> getWayTag(long osmId, Map<Long, Map<String, String>> osmIdToWayTags) {
        return osmIdToWayTags.getOrDefault(osmId, null);
    }

    public static String getTagValueFromOsmWay(ReaderWay way, String tagName) {
        if (way.hasTag(tagName)) {
            return way.getTag(tagName);
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

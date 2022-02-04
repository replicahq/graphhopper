package com.graphhopper.replica;

import java.util.List;
import java.util.Map;

public class OsmHelper {

    public static Map<String, String> getLanesTag(long osmId, Map<Long, Map<String, String>> osmIdToLaneTags) {
        return osmIdToLaneTags.getOrDefault(osmId, null);
    }

    public static long getOsmIdForGhEdge(int ghEdgeId, Map<Integer, Long> ghIdToOsmId) {
        return ghIdToOsmId.getOrDefault(ghEdgeId, -1L);
    }

    // Sets of flags are returned for each edge direction, stored in a List<String> ordered [forward, backward]
    public static String getFlagsForGhEdge(int ghEdgeId, boolean reverse, Map<Long, List<String>> osmIdToAccessFlags,
                                           Map<Integer, Long> ghIdToOsmId) {
        int flagIndex = reverse ? 1 : 0;
        return osmIdToAccessFlags.get(getOsmIdForGhEdge(ghEdgeId, ghIdToOsmId)).get(flagIndex);
    }
}

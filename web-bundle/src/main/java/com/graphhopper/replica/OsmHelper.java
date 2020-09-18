package com.graphhopper.replica;

import com.graphhopper.export.CustomGraphHopperGtfs;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;

public class OsmHelper {
    private static final Logger logger = LoggerFactory.getLogger(OsmHelper.class);

    public static void writeOsmInfoToMapDb(CustomGraphHopperGtfs graphHopperGtfs) {
        logger.info("Initializing new MapDB database files to store OSM info.");
        DB db = DBMaker.newFileDB(new File("transit_data/osm_info.db")).make();

        HTreeMap<Long, Map<String, String>> osmIdToLaneTags = db
                .createHashMap("osmIdToLaneTags")
                .keySerializer(Serializer.LONG)
                .valueSerializer(Serializer.JAVA)
                .make();

        HTreeMap<Integer, Long> ghIdToOsmId = db
                .createHashMap("ghIdToOsmId")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.LONG)
                .make();

        HTreeMap<Long, List<String>> osmIdToAccessFlags = db
                .createHashMap("osmIdToAccessFlags")
                .keySerializer(Serializer.LONG)
                .valueSerializer(Serializer.JAVA)
                .make();

        osmIdToLaneTags.putAll(graphHopperGtfs.getOsmIdToLaneTags());
        ghIdToOsmId.putAll(graphHopperGtfs.getGhIdToOsmId());
        osmIdToAccessFlags.putAll(graphHopperGtfs.getOsmIdToAccessFlags());

        db.commit();
        db.close();
        logger.info("Done writing OSM info to MapDB database files.");
    }

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

package com.replica;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Stop;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.GtfsStorage;
import com.graphhopper.replica.GtfsLinkMapperHelper;
import com.graphhopper.util.Helper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

public class GtfsLinkMapperTest extends ReplicaGraphHopperTest {
    private static final Logger logger = LoggerFactory.getLogger(GtfsLinkMapperTest.class);
    private static final String TEST_FEED_NAME = "link_mapping_test_feed";
    private static final String TEST_GRAPHHOPPER_CONFIG_PATH = "../test_gh_config_one_feed.yaml";
    private static final String GRAPH_FILES_DIR = "transit_data/link_mapper/";

    @BeforeAll
    public static void setup() throws Exception {
        // If graph files exist from main unit tests, copy to tmp location
        File existingGraphFolder = new File("./transit_data");
        if (existingGraphFolder.exists()) {
            logger.info("Existing graph files being moved to tmp_transit_data folder temporarily");
            FileUtils.copyDirectory(existingGraphFolder, new File("./tmp_transit_data"));
        }
        setup(TEST_GRAPHHOPPER_CONFIG_PATH, GRAPH_FILES_DIR);
    }

    @Test
    public void testSingleFeed() {
        File linkMappingsDbFile = new File("transit_data/gtfs_link_mappings.db");
        assert linkMappingsDbFile.exists();
        DB db = DBMaker.newFileDB(linkMappingsDbFile).readOnly().make();
        Map<String, String> gtfsLinkMappings = db.getHashMap("gtfsLinkMappings");
        Map<String, List<String>> gtfsRouteInfo = db.getHashMap("gtfsRouteInfo");
        Map<String, String> gtfsFeedIdMapping = db.getHashMap("gtfsFeedIdMap");

        GtfsStorage gtfsStorage = ((GraphHopperGtfs) graphHopperManaged.getGraphHopper()).getGtfsStorage();
        Map<String, GTFSFeed> gtfsFeedMap = gtfsStorage.getGtfsFeeds();
        assert gtfsFeedMap.keySet().size() == 1;

        Map.Entry<String, GTFSFeed> entry = gtfsFeedMap.entrySet().iterator().next();
        GTFSFeed feed = entry.getValue();
        SetMultimap<String, Pair<Stop, Stop>> routeIdToStopPairs = GtfsLinkMapperHelper.extractStopPairsFromFeed(feed);
        assert routeIdToStopPairs.keySet().size() == 1;

        // Check that at least 95% of eligible stop-stop pairs were routed between
        Set<Pair<Stop, Stop>> uniqueStopPairs = Sets.newHashSet(routeIdToStopPairs.values());
        double routedRatio = (double) uniqueStopPairs.size() / gtfsLinkMappings.size();
        assert routedRatio >= 0.95;

        // Check that 100% of mappings present have stable edge IDs
        long mappingsWithIdsCount = gtfsLinkMappings.entrySet().stream().filter(m -> m.getValue().length() > 0).count();
        assert mappingsWithIdsCount == gtfsLinkMappings.size();

        // Check contents of route info + feed ID maps
        assert gtfsFeedIdMapping.size() == 1;
        String graphHopperFeedName = gtfsFeedIdMapping.keySet().iterator().next();
        assert gtfsFeedIdMapping.get(graphHopperFeedName).equals(TEST_FEED_NAME);

        assert gtfsRouteInfo.size() == 1;
        String routeId = routeIdToStopPairs.keySet().iterator().next();
        String routeInfoKey = graphHopperFeedName + ":" + routeId;
        // routeInfo contains agency_name,route_short_name,route_long_name,route_type
        List<String> routeInfo = gtfsRouteInfo.get(routeInfoKey);
        assert routeInfo.get(0).equals("Sacramento Regional Transit");
        assert routeInfo.get(1).equals("1");
        assert routeInfo.get(2).equals("GREENBACK");
        assert routeInfo.get(3).equals("3");
    }

    @Test
    public void testNoStreetBasedRoutes() throws Exception {
        GTFSFeed feed = new GTFSFeed();
        feed.loadFromFileAndLogErrors(new ZipFile("./test-data/link_mapping_test_feed.zip"));

        // Set each route's route type to a non-street-based route type
        feed.routes.values().forEach(r -> r.route_type = 1);
        SetMultimap<String, Pair<Stop, Stop>> routeIdToStopPairs = GtfsLinkMapperHelper.extractStopPairsFromFeed(feed);
        assert routeIdToStopPairs.size() == 0;
    }

    @AfterAll
    public static void cleanupGraphDir() throws Exception {
        Helper.removeDir(new File(GRAPH_FILES_DIR));
        Helper.removeDir(new File(TRANSIT_DATA_DIR));
        closeGraphhopper();

        // If we copied existing graph files before running tests, copy back from tmp location
        File existingGraphFolder = new File("./tmp_transit_data");
        if (existingGraphFolder.exists()) {
            logger.info("Already-existing graph files being moved back to transit_data from temp location");
            FileUtils.copyDirectory(existingGraphFolder, new File("./transit_data"));
        }

        // Reload default test graph files so remaining unit tests work
        loadGraphhopper();
    }
}

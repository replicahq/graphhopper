package com.graphhopper.http.cli;

import com.google.common.collect.Lists;
import com.graphhopper.GraphHopper;
import com.graphhopper.http.GraphHopperManaged;
import com.graphhopper.http.GraphHopperServerConfiguration;
import com.graphhopper.replica.OsmHelper;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.stableid.StableIdEncodedValues;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Custom command used to export a GraphHopper street network in CSV format. Developed to mimic as much as possible
 * the logic from R5's built-in street network export command.
 *
 * To run, the command expects an OSM file for the region to be specified with GraphHopper's
 * -Ddw.graphhopper.datareader.file command line argument, and pre-built graph files (built from the same OSM using
 * GH's import command) to be present in the /transit_data/graphhopper subfolder.
 *
 * Example of calling this command:
 * java -Xmx10g -Ddw.graphhopper.datareader.file=./region_cutout.osm.pbf \
 * -jar web/target/graphhopper-web-1.0-SNAPSHOT.jar export config-usa.yml
 */

public class ExportCommand extends ConfiguredCommand<GraphHopperServerConfiguration> {
    private static final Logger logger = LoggerFactory.getLogger(ExportCommand.class);

    private static final List<String> HIGHWAY_FILTER_TAGS = Lists.newArrayList("bridleway", "steps");
    private static final String[] COLUMN_HEADERS = {"stableEdgeId", "startVertex", "endVertex", "startLat", "startLon",
            "endLat", "endLon", "geometry", "streetName", "distance", "osmid", "speed", "flags", "lanes", "highway"};

    public ExportCommand() {
        super("export", "Generates street network CSV file from a GH graph");
    }

    @Override
    protected void run(Bootstrap<GraphHopperServerConfiguration> bootstrap, Namespace namespace,
                       GraphHopperServerConfiguration configuration) {
        // Read in pre-built GH graph files from /transit_data/graphhopper
        final GraphHopperManaged graphHopper =
                new GraphHopperManaged(configuration.getGraphHopperConfiguration(), bootstrap.getObjectMapper());
        GraphHopper configuredGraphHopper = graphHopper.getGraphHopper();
        if (!configuredGraphHopper.load(configuredGraphHopper.getGraphHopperLocation())) {
            throw new RuntimeException("Couldn't load existing GH graph at " +
                    configuredGraphHopper.getGraphHopperLocation());
        }

        // Load OSM info needed for export from MapDB database file
        DB db = DBMaker.newFileDB(new File("transit_data/osm_info.db")).make();
        Map<Long, Map<String, String>> osmIdToLaneTags = db.getHashMap("osmIdToLaneTags");
        Map<Integer, Long> ghIdToOsmId = db.getHashMap("ghIdToOsmId");
        Map<Long, List<String>> osmIdToAccessFlags = db.getHashMap("osmIdToAccessFlags");
        Map<Long, String> osmIdToStreetName = db.getHashMap("osmIdToStreetName");
        Map<Long, String> osmIdToHighway = db.getHashMap("osmIdToHighway");
        logger.info("Done loading OSM info needed for CSV export from MapDB file.");

        // Use loaded graph data to write street network out to CSV
        writeStreetEdgesCsv(configuredGraphHopper, osmIdToLaneTags, ghIdToOsmId,
                osmIdToAccessFlags, osmIdToStreetName, osmIdToHighway);
        db.close();
    }

    private static void writeStreetEdgesCsv(GraphHopper configuredGraphHopper,
                                            Map<Long, Map<String, String>> osmIdToLaneTags,
                                            Map<Integer, Long> ghIdToOsmId,
                                            Map<Long, List<String>> osmIdToAccessFlags,
                                            Map<Long, String> osmIdToStreetName,
                                            Map<Long, String> osmIdToHighway) {

        // Grab edge/node iterators for graph loaded from pre-built GH files
        GraphHopperStorage graphHopperStorage = configuredGraphHopper.getGraphHopperStorage();
        AllEdgesIterator edgeIterator = graphHopperStorage.getAllEdges();
        NodeAccess nodes = graphHopperStorage.getNodeAccess();

        // Setup encoders for determining speed and road type info for each edge
        EncodingManager encodingManager = configuredGraphHopper.getEncodingManager();
        StableIdEncodedValues stableIdEncodedValues = StableIdEncodedValues.fromEncodingManager(encodingManager);
        final EnumEncodedValue<RoadClass> roadClassEnc =
                encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        CarFlagEncoder carFlagEncoder = (CarFlagEncoder)encodingManager.getEncoder("car");
        DecimalEncodedValue avgSpeedEnc = carFlagEncoder.getAverageSpeedEnc();

        logger.info("Writing street edges...");
        File outputFile = new File(configuredGraphHopper.getGraphHopperLocation() + "/street_edges.csv");

        // For each bidirectional edge in pre-built graph, calculate value of each CSV column
        // and export new line for each edge direction
        int totalEdgeCount = 0;
        int skippedEdgeCount = 0;
        try {
            FileWriter out = new FileWriter(outputFile);
            try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withHeader(COLUMN_HEADERS))) {
                while (edgeIterator.next()) {
                    totalEdgeCount++;
                    // Fetch starting and ending vertices
                    int ghEdgeId = edgeIterator.getEdge();
                    int startVertex = edgeIterator.getBaseNode();
                    int endVertex = edgeIterator.getAdjNode();
                    double startLat = nodes.getLat(startVertex);
                    double startLon = nodes.getLon(startVertex);
                    double endLat = nodes.getLat(endVertex);
                    double endLon = nodes.getLon(endVertex);

                    // Get edge geometry and distance
                    PointList wayGeometry = edgeIterator.fetchWayGeometry(FetchMode.ALL);
                    String geometryString = wayGeometry.toLineString(false).toString();

                    //todo : which distance calc to use?
                    // edgeIterator.getDistance();
                    // DistanceCalcEarth.DIST_EARTH.calcDist(startLat, startLon, endLat, endLon);
                    long distanceMeters = Math.round(DistanceCalcEarth.DIST_EARTH.calcDist(startLat, startLon, endLat, endLon));

                    // Convert GH's km/h speed to cm/s to match R5's implementation
                    int speedcms = (int) (edgeIterator.get(avgSpeedEnc) / 3.6 * 100);

                    // Convert GH's distance in meters to millimeters to match R5's implementation
                    long distanceMillimeters = distanceMeters * 1000;

                    // Fetch OSM ID, skipping edges from PT meta-graph that have no IDs set (getOsmIdForGhEdge returns -1)
                    long osmId = OsmHelper.getOsmIdForGhEdge(edgeIterator.getEdge(), ghIdToOsmId);
                    if (osmId == -1L) {
                        skippedEdgeCount++;
                        continue;
                    }

                    // Use street name parsed from Ways/Relations, if it exists; otherwise, use default GH edge name
                    String streetName = osmIdToStreetName.getOrDefault(osmId, edgeIterator.getName());

                    // Grab OSM highway type and encoded stable IDs for both edge directions
                    String highwayTag = osmIdToHighway.getOrDefault(osmId, edgeIterator.get(roadClassEnc).toString());
                    String forwardStableEdgeId = stableIdEncodedValues.getStableId(false, edgeIterator);
                    String backwardStableEdgeId = stableIdEncodedValues.getStableId(true, edgeIterator);

                    // Set accessibility flags for each edge direction
                    // Returned flags are from the set {ALLOWS_CAR, ALLOWS_BIKE, ALLOWS_PEDESTRIAN}
                    String forwardFlags = OsmHelper.getFlagsForGhEdge(ghEdgeId, false, osmIdToAccessFlags, ghIdToOsmId);
                    String backwardFlags = OsmHelper.getFlagsForGhEdge(ghEdgeId, true, osmIdToAccessFlags, ghIdToOsmId);

                    // Calculate number of lanes for edge, as done in R5, based on OSM tags + edge direction
                    int overallLanes = parseLanesTag(osmId, osmIdToLaneTags, "lanes");
                    int forwardLanes = parseLanesTag(osmId, osmIdToLaneTags, "lanes:forward");
                    int backwardLanes = parseLanesTag(osmId, osmIdToLaneTags, "lanes:backward");

                    if (!backwardFlags.contains("ALLOWS_CAR")) {
                        backwardLanes = 0;
                    }
                    if (backwardLanes == -1) {
                        if (overallLanes != -1) {
                            if (forwardLanes != -1) {
                                backwardLanes = overallLanes - forwardLanes;
                            }
                        }
                    }

                    if (!forwardFlags.contains("ALLOWS_CAR")) {
                        forwardLanes = 0;
                    }
                    if (forwardLanes == -1) {
                        if (overallLanes != -1) {
                            if (backwardLanes != -1) {
                                forwardLanes = overallLanes - backwardLanes;
                            } else if (forwardFlags.contains("ALLOWS_CAR")) {
                                forwardLanes = overallLanes / 2;
                            }
                        }
                    }

                    // Copy R5's logic; filter out edges with unwanted highway tags and negative OSM IDs
                    // todo: do negative OSM ids happen in GH? This might have been R5-specific
                    if (!HIGHWAY_FILTER_TAGS.contains(highwayTag) && osmId >= 0) {
                        // Print line for each edge direction
                        printer.printRecord(forwardStableEdgeId, startVertex, endVertex,
                                startLat, startLon, endLat, endLon, geometryString, streetName,
                                distanceMillimeters, osmId, speedcms, forwardFlags, forwardLanes, highwayTag);
                        printer.printRecord(backwardStableEdgeId, endVertex, startVertex,
                                endLat, endLon, startLat, startLon, geometryString, streetName,
                                distanceMillimeters, osmId, speedcms, backwardFlags, backwardLanes, highwayTag);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("IOException raised while writing street network to csv!");
            throw new RuntimeException(e);
        }
        logger.info("Done writing street network to CSV");
        logger.info("A total of " + totalEdgeCount + " edges were considered; " + skippedEdgeCount + " edges were skipped");
        if (!outputFile.exists()) {
            logger.error("Output file can't be found! Export may not have completed successfully");
        }
    }

    // Taken from R5's lane parsing logic. See EdgeServiceServer.java in R5 repo
    private static int parseLanesTag(long osmId, Map<Long, Map<String, String>> osmIdToLaneTags, String laneTag) {
        int result = -1;
        Map<String, String> laneTagsOnEdge = OsmHelper.getLanesTag(osmId, osmIdToLaneTags);
        if (laneTagsOnEdge != null) {
            if (laneTagsOnEdge.containsKey(laneTag)) {
                try {
                    return parseLanesTag(laneTagsOnEdge.get(laneTag));
                } catch (NumberFormatException ex) {
                    logger.warn("way {}: Unable to parse lanes value as number {}", osmId, laneTag);
                }
            }
        }
        return result;
    }

    static int parseLanesTag(String tagValue) {
        double[] values = Arrays.stream(tagValue.split(";"))
                .mapToDouble(Double::parseDouble)
                .toArray();
        Arrays.sort(values);
        double median;
        if (values.length % 2 == 0) {
            median = values[values.length / 2 - 1];
        } else {
            median = values[values.length / 2];
        }
        return (int) median;
    }
}

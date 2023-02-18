package com.graphhopper.replica;

import com.google.common.collect.Lists;
import com.graphhopper.GraphHopper;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.*;
import com.graphhopper.stableid.StableIdEncodedValues;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.glassfish.jersey.internal.guava.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class StreetEdgeExporter {
    private static final Logger logger = LoggerFactory.getLogger(StreetEdgeExporter.class);

    private static final Map<String, String> ACCESSIBILITY_MODE_MAP = Map.of(
            "car", "CAR",
            "bike", "BIKE",
            "foot", "PEDESTRIAN"
    );
    private static final List<String> HIGHWAY_FILTER_TAGS = Lists.newArrayList("bridleway", "steps");
    private static final List<String> INACCESSIBLE_MOTORWAY_TAGS = Lists.newArrayList("motorway", "motorway_link");
    private static final String[] COLUMN_HEADERS = {"stableEdgeId", "humanReadableStableEdgeId", "startVertex", "endVertex", "startLat", "startLon",
            "endLat", "endLon", "geometry", "streetName", "distance", "osmid", "speed", "flags", "lanes", "highway",
            "startOsmNode", "endOsmNode"};
    public static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.withHeader(COLUMN_HEADERS);

    // Some sticky members
    private Map<Long, Map<String, String>> osmIdToLaneTags;
    private Map<Long, String> osmIdToStreetName;
    private Map<Long, String> osmIdToHighway;

    private NodeAccess nodes;
    private DecimalEncodedValue avgSpeedEnc;
    private StableIdEncodedValues stableIdEncodedValues;
    private EnumEncodedValue<RoadClass> roadClassEnc;
    private IntEncodedValue osmWayIdEnc;
    private EncodingManager encodingManager;
    private OsmHelper osmHelper;

    public StreetEdgeExporter(GraphHopper configuredGraphHopper,
                              Map<Long, Map<String, String>> osmIdToLaneTags,
                              Map<Long, String> osmIdToStreetName,
                              Map<Long, String> osmIdToHighway,
                              OsmHelper osmHelper) {
        this.osmIdToLaneTags = osmIdToLaneTags;
        this.osmIdToStreetName = osmIdToStreetName;
        this.osmIdToHighway = osmIdToHighway;
        this.osmHelper = osmHelper;

        // Grab edge/node iterators for graph loaded from pre-built GH files
        this.nodes = configuredGraphHopper.getBaseGraph().getNodeAccess();

        // Setup encoders for determining speed and road type info for each edge
        this.encodingManager = configuredGraphHopper.getEncodingManager();
        this.stableIdEncodedValues = StableIdEncodedValues.fromEncodingManager(this.encodingManager, osmHelper);
        this.roadClassEnc = this.encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        this.avgSpeedEnc = this.encodingManager.getDecimalEncodedValue(VehicleSpeed.key("car"));
        this.osmWayIdEnc = this.encodingManager.getIntEncodedValue("osmid");
    }

    public List<StreetEdgeExportRecord> generateRecords(EdgeIteratorState iteratorState) {
        List<StreetEdgeExportRecord> output = new ArrayList<>();

        int ghEdgeId = iteratorState.getEdge();
        int startVertex = iteratorState.getBaseNode();
        int endVertex = iteratorState.getAdjNode();
        double startLat = nodes.getLat(startVertex);
        double startLon = nodes.getLon(startVertex);
        double endLat = nodes.getLat(endVertex);
        double endLon = nodes.getLon(endVertex);

        // Get edge geometry for both edge directions, and distance
        PointList wayGeometry = iteratorState.fetchWayGeometry(FetchMode.ALL);
        String geometryString = wayGeometry.toLineString(false).toString();
        wayGeometry.reverse();
        String reverseGeometryString = wayGeometry.toLineString(false).toString();

        long distanceMeters = Math.round(DistanceCalcEarth.DIST_EARTH.calcDist(startLat, startLon, endLat, endLon));
        // Convert GH's km/h speed to cm/s to match R5's implementation
        int speedcms = (int) (iteratorState.get(avgSpeedEnc) / 3.6 * 100);

        // Convert GH's distance in meters to millimeters to match R5's implementation
        long distanceMillimeters = distanceMeters * 1000;

        // Fetch OSM Way ID, skipping edges that have no IDs set (getOSMWay returns -1)
        long osmWayId = iteratorState.get(osmWayIdEnc);
        if (osmWayId <= 0L) {
            throw new RuntimeException("OSM Way ID <= 0 for edge " + ghEdgeId + "! This shouldn't happen");
        }

        // Fetch OSM Node IDs for each node of edge
        long startOsmNode = osmHelper.getOSMNode(startVertex);
        long endOsmNode = osmHelper.getOSMNode(endVertex);

        // Check if start or end node IDs are artificial IDs; if so, replace them with real IDs
        if (startOsmNode <= 0) {
            startOsmNode = osmHelper.getRealNodeIdFromArtificial(startOsmNode);
        }
        if (endOsmNode <= 0) {
            endOsmNode = osmHelper.getRealNodeIdFromArtificial(endOsmNode);
        }

        if (startOsmNode <= 0 || endOsmNode <= 0) {
            throw new RuntimeException("Start or end OSM node ID is <= 0! This shouldn't happen");
        }

        // Filter out single-point "edges" + edges with identical start/end point locations
        // and no intermediate points (as would exist in the case of road loops)
        if (wayGeometry.size() <= 1) {
            return output;
        } else if (wayGeometry.size() == 2 && (wayGeometry.get(0).equals(wayGeometry.get(1)))) {
            return output;
        }

        // Use street name parsed from Ways/Relations, if it exists; otherwise, use default GH edge name
        String streetName = osmIdToStreetName.getOrDefault(osmWayId, iteratorState.getName());

        // Grab OSM highway type and encoded stable IDs for both edge directions
        String highwayTag = osmIdToHighway.getOrDefault(osmWayId, iteratorState.get(roadClassEnc).toString());
        String forwardStableEdgeId = stableIdEncodedValues.getStableId(false, iteratorState);
        String backwardStableEdgeId = stableIdEncodedValues.getStableId(true, iteratorState);

        int segmentIndex = osmHelper.getSegmentIndexForGhEdge(ghEdgeId);
        String humanReadableForwardStableEdgeId = StableIdEncodedValues.calculateHumanReadableStableEdgeId(osmWayId, segmentIndex, false);
        String humanReadableBackwardStableEdgeId = StableIdEncodedValues.calculateHumanReadableStableEdgeId(osmWayId, segmentIndex, true);

        // Set accessibility flags for each edge direction
        // Returned flags are from the set {ALLOWS_CAR, ALLOWS_BIKE, ALLOWS_PEDESTRIAN}
        IntsRef edgeFlags = iteratorState.getFlags();
        Set<String> forwardFlags = Sets.newHashSet();
        Set<String> backwardFlags = Sets.newHashSet();
        for (String vehicle: encodingManager.getVehicles()) {
            // Only record accessibility for car, bike, and foot vehicles
            String mode = ACCESSIBILITY_MODE_MAP.getOrDefault(vehicle, null);
            if (mode != null) {
                BooleanEncodedValue access = encodingManager.getBooleanEncodedValue(VehicleAccess.key(vehicle));
                if (access.getBool(false, edgeFlags)) {
                    forwardFlags.add("ALLOWS_" + mode);
                }
                if (access.getBool(true, edgeFlags)) {
                    backwardFlags.add("ALLOWS_" + mode);
                }
            }
        }

        // Calculate number of lanes for edge, as done in R5, based on OSM tags + edge direction
        int overallLanes = parseLanesTag(osmWayId, osmIdToLaneTags, "lanes");
        int forwardLanes = parseLanesTag(osmWayId, osmIdToLaneTags, "lanes:forward");
        int backwardLanes = parseLanesTag(osmWayId, osmIdToLaneTags, "lanes:backward");

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

        // Filter out edges with unwanted highway tags
        if (!HIGHWAY_FILTER_TAGS.contains(highwayTag)) {
            // Print line for each edge direction, if edge is accessible; inaccessible edges should have
            // no flags set. Only remove inaccessible edges with highway tags of motorway or motorway_link
            if (!(forwardFlags.isEmpty() && INACCESSIBLE_MOTORWAY_TAGS.contains(highwayTag))) {
                output.add(new StreetEdgeExportRecord(forwardStableEdgeId, humanReadableForwardStableEdgeId,
                        startVertex, endVertex, startLat, startLon, endLat, endLon, geometryString, streetName,
                        distanceMillimeters, osmWayId, speedcms, forwardFlags.toString(), forwardLanes, highwayTag, startOsmNode, endOsmNode));
            }
            if (!(backwardFlags.isEmpty() && INACCESSIBLE_MOTORWAY_TAGS.contains(highwayTag))) {
                output.add(new StreetEdgeExportRecord(backwardStableEdgeId, humanReadableBackwardStableEdgeId,
                        endVertex, startVertex, endLat, endLon, startLat, startLon, reverseGeometryString, streetName,
                        distanceMillimeters, osmWayId, speedcms, backwardFlags.toString(), backwardLanes, highwayTag, endOsmNode, startOsmNode));
            }
        }

        return output;
    }

    public static void writeStreetEdgesCsv(GraphHopper configuredGraphHopper,
                                           Map<Long, Map<String, String>> osmIdToLaneTags,
                                           Map<Long, String> osmIdToStreetName,
                                           Map<Long, String> osmIdToHighway,
                                           OsmHelper osmHelper) {
        StreetEdgeExporter exporter = new StreetEdgeExporter(configuredGraphHopper, osmIdToLaneTags,
                osmIdToStreetName, osmIdToHighway, osmHelper);
        AllEdgesIterator edgeIterator = configuredGraphHopper.getBaseGraph().getAllEdges();
        File outputFile = new File(configuredGraphHopper.getGraphHopperLocation() + "/street_edges.csv");

        logger.info("Writing street edges...");

        // For each bidirectional edge in pre-built graph, calculate value of each CSV column
        // and export new line for each edge direction
        int totalEdgeCount = 0;
        int skippedEdgeCount = 0;
        try {
            FileWriter out = new FileWriter(outputFile);
            try (CSVPrinter printer = new CSVPrinter(out, CSV_FORMAT)) {
                while (edgeIterator.next()) {
                    totalEdgeCount++;
                    List<StreetEdgeExportRecord> records = exporter.generateRecords(edgeIterator);
                    if(records.isEmpty()){
                        skippedEdgeCount++;
                    }
                    for(StreetEdgeExportRecord r : records) {
                        printer.printRecord(r.edgeId, r.humanReadableEdgeId, r.startVertexId, r.endVertexId, r.startLat, r.startLon, r.endLat, r.endLon,
                                r.geometryString, r.streetName, r.distanceMillimeters, r.osmId, r.speedCms, r.flags, r.lanes, r.highwayTag,
                                r.startOsmNode, r.endOsmNode);
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

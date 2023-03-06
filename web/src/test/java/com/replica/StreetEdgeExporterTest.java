package com.replica;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.graphhopper.CustomGraphHopperGtfs;
import com.graphhopper.GraphHopper;
import com.graphhopper.replica.StreetEdgeExportRecord;
import com.graphhopper.replica.StreetEdgeExporter;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.util.Helper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({ReplicaGraphHopperTestExtention.class})
public class StreetEdgeExporterTest extends ReplicaGraphHopperTest {

    @Test
    public void testExportEndToEnd() throws IOException {
        CSVFormat format = StreetEdgeExporter.CSV_FORMAT;
        File expectedOutputLocation = new File(EXPORT_FILES_DIR + "street_edges.csv");
        CSVParser parser = CSVParser.parse(expectedOutputLocation, StandardCharsets.UTF_8, format);
        List<CSVRecord> records = parser.getRecords();
        assertEquals(1147753, records.size());

        // Check for well-formed vehicle names in accessibility flags
        int nullAccessibilityFlagCount = 0;

        // Sanity check OSM node + edge coverage and stable edge ID uniqueness
        int emptyNodeIdCount = 0;
        int emptyWayIdCount = 0;
        Set<String> observedStableEdgeIds = Sets.newHashSet();
        Set<String> observedSegmentIds = Sets.newHashSet();

        // Remove header row
        records.remove(0);

        Map<Long, Integer> osmIdToNumSubsegments = Maps.newHashMap();
        Map<Long, Integer> osmIdToHighestSubsegment = Maps.newHashMap();

        for (CSVRecord record : records) {
            observedStableEdgeIds.add(record.get("stableEdgeId"));
            observedSegmentIds.add(record.get("segmentId"));

            long osmId = Long.parseLong(record.get("osmid"));

            if (Long.parseLong(record.get("startOsmNode")) <= 0) emptyNodeIdCount++;
            if (Long.parseLong(record.get("endOsmNode")) <= 0) emptyNodeIdCount++;
            if (osmId <= 0) emptyWayIdCount++;
            if (record.get("flags").contains("null")) nullAccessibilityFlagCount++;

            String subsegmentSuffix = record.get("segmentId").split("_")[1];
            if (subsegmentSuffix.endsWith("+")) {
                // Record how many subsegments appear for each OSM Way
                if (!osmIdToNumSubsegments.containsKey(osmId)) {
                    osmIdToNumSubsegments.put(osmId, 1);
                } else {
                    osmIdToNumSubsegments.put(osmId, osmIdToNumSubsegments.get(osmId) + 1);
                }

                // Record higheset subsegment index found for each forward (+) OSM Way
                int subsegmentIndex = Integer.parseInt(subsegmentSuffix.substring(0, subsegmentSuffix.length() - 1));
                if (!osmIdToHighestSubsegment.containsKey(osmId)) {
                    osmIdToHighestSubsegment.put(osmId, subsegmentIndex);
                } else if (osmIdToHighestSubsegment.get(osmId) < subsegmentIndex) {
                    osmIdToHighestSubsegment.put(osmId, subsegmentIndex);
                }
            }
        }
        assertEquals(0, emptyNodeIdCount); // no empty/negative OSM node IDs
        assertEquals(0, emptyWayIdCount); // no empty/negative OSM way IDs
        assertEquals(records.size(), observedStableEdgeIds.size()); // fully unique stable edge IDs
        assertEquals(records.size(), observedSegmentIds.size()); // fully unique segment IDs
        assertEquals(0, nullAccessibilityFlagCount); // no badly-formed vehicles appear in accessibility flags

        // For every OSM Way, check that the number of recorded subsegments matches
        // the highest-seen subsegment index in that Way's segment IDs
        // This ensures we catch any cases where a given subsegment in the start or middle
        // of an OSM way doesn't have a corresponding link in our export, but note that it
        // doesn't catch the case where the final subsegment isn't there.
        //
        // Also note that we don't force 100% accuracy here; this is due to a tiny number of edge
        // cases that cause us to drop subsegments that technically should be output.
        // One known case is due to the fact that when we filter out edges with identical start/end
        // points, we use a GH-specific class's comparison function, which rounds each lat/lon
        // slightly. So, very short edges that technically should be output can be filtered out.
        // However, I haven't figured out how to fix this (even with checking exact equality),
        // because then certain "bad" edges - mainly fake edges added due to barrier nodes - start
        // getting output, and we lose stable edge ID uniqueness
        int numMissingSegmentIndexes = 0;
        for (Long osmId : osmIdToNumSubsegments.keySet()) {
            // Add 1 to the highest-seen subsegment index, because the indices start at 0
            if (osmIdToNumSubsegments.get(osmId) != osmIdToHighestSubsegment.get(osmId) + 1) {
                numMissingSegmentIndexes++;
            }
        }
        assertTrue(numMissingSegmentIndexes < 5);

        Helper.removeDir(new File(EXPORT_FILES_DIR));
    }

    @Test
    public void testExportSingleRecord() throws Exception {
        // Load graph and run OSM parsing step to pull tag info from OSM
        GraphHopper configuredGraphHopper = graphHopperManaged.getGraphHopper();
        CustomGraphHopperGtfs gh = (CustomGraphHopperGtfs) configuredGraphHopper;
        gh.collectOsmInfo();

        // Copied from writeStreetEdgesCsv
        StreetEdgeExporter exporter = new StreetEdgeExporter(
                configuredGraphHopper, gh.getOsmIdToLaneTags(), gh.getOsmIdToStreetName(), gh.getOsmIdToHighwayTag(), gh.getOsmHelper()
        );
        AllEdgesIterator edgeIterator = configuredGraphHopper.getBaseGraph().getAllEdges();

        // Generate the rows for the first item in the edge iterator
        edgeIterator.next();
        List<StreetEdgeExportRecord> records = exporter.generateRecords(edgeIterator);
        // Expect that two items will be generated
        assertEquals(2, records.size());
        // They should be each other's reverse edges
        StreetEdgeExportRecord record0 = records.get(0);
        StreetEdgeExportRecord record1 = records.get(1);
        assertEquals(record0.startVertexId, record1.endVertexId);
        assertEquals(record0.endVertexId, record1.startVertexId);
        assertEquals(record0.startLat, record1.endLat);
        assertEquals(record0.startLon, record1.endLon);
    }
}

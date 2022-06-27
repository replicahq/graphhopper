package com.replica;

import com.google.common.collect.Sets;
import com.graphhopper.CustomGraphHopperGtfs;
import com.graphhopper.GraphHopper;
import com.graphhopper.replica.StreetEdgeExportRecord;
import com.graphhopper.replica.StreetEdgeExporter;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.Helper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

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
        assertEquals(1095756, records.size());

        // Sanity check OSM node + edge coverage and stable edge ID uniqueness
        int emptyNodeIdCount = 0;
        int emptyWayIdCount = 0;
        Set<String> observedStableEdgeIds = Sets.newHashSet();
        for (int i = 1; i < 10001; i++) {
            CSVRecord record = records.get(i);
            observedStableEdgeIds.add(record.get("stableEdgeId"));
            if (Long.parseLong(record.get("startOsmNode")) <= 0) emptyNodeIdCount++;
            if (Long.parseLong(record.get("endOsmNode")) <= 0) emptyNodeIdCount++;
            if (Long.parseLong(record.get("osmid")) <= 0) emptyWayIdCount++;
        }
        assertEquals(0, emptyNodeIdCount);
        assertEquals(0, emptyWayIdCount);
        assertEquals(10000, observedStableEdgeIds.size());

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
        GraphHopperStorage graphHopperStorage = configuredGraphHopper.getGraphHopperStorage();
        AllEdgesIterator edgeIterator = graphHopperStorage.getAllEdges();

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

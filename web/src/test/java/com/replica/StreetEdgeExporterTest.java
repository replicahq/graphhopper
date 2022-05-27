package com.replica;

import com.google.common.collect.Maps;
import com.graphhopper.CustomGraphHopperGtfs;
import com.graphhopper.GraphHopper;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.replica.StreetEdgeExportRecord;
import com.graphhopper.replica.StreetEdgeExporter;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.DAType;
import com.graphhopper.storage.GHDirectory;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith({ReplicaGraphHopperTestExtention.class})
public class StreetEdgeExporterTest extends ReplicaGraphHopperTest {

    @Test
    public void testExportEndToEnd() throws IOException {
        CSVFormat format = StreetEdgeExporter.CSV_FORMAT;
        File expectedOutputLocation = new File(EXPORT_FILES_DIR + "street_edges.csv");
        CSVParser parser = CSVParser.parse(expectedOutputLocation, StandardCharsets.UTF_8, format);
        List<CSVRecord> records = parser.getRecords();
        assertEquals(1109143, records.size());
        Helper.removeDir(new File(EXPORT_FILES_DIR));
    }

    @Test
    public void testExportSingleRecord() throws Exception {
        // Load graph and run OSM parsing step to pull tag info from OSM
        GraphHopper configuredGraphHopper = graphHopperManaged.getGraphHopper();
        CustomGraphHopperGtfs gh = (CustomGraphHopperGtfs) configuredGraphHopper;
        gh.collectOsmInfo();

        // Hacky override used to populate GH ID -> OSM ID map, normally stored in-memory during nationwide export
        // All other OSM-derived info is parsed in above call to collectOsmInfo()
        Map<Integer, Long> ghIdToOsmId = Maps.newHashMap();
        GraphHopperStorage s = new GraphHopperStorage(new GHDirectory(EXPORT_FILES_DIR, DAType.RAM_STORE), gh.getEncodingManager(), false);
        OSMReader reader = new OSMReader(s) {
            @Override
            protected void storeOsmWayID(int edgeId, long osmWayId) {
                super.storeOsmWayID(edgeId, osmWayId);
                ghIdToOsmId.put(edgeId, osmWayId);
            }
        };
        reader.setFile(new File(gh.getOSMFile()));
        reader.readGraph();

        // Copied from writeStreetEdgesCsv
        StreetEdgeExporter exporter = new StreetEdgeExporter(
                configuredGraphHopper, gh.getOsmIdToLaneTags(), ghIdToOsmId,
                gh.getOsmIdToAccessFlags(), gh.getOsmIdToStreetName(), gh.getOsmIdToHighwayTag()
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

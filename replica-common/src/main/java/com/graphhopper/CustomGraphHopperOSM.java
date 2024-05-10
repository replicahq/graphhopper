package com.graphhopper;

import com.google.common.collect.Maps;
import com.graphhopper.reader.osm.CustomOsmReader;
import com.graphhopper.routing.util.AreaIndex;
import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.storage.DAType;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.GHDirectory;
import com.graphhopper.util.BitUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.graphhopper.util.GHUtility.readCountries;
import static com.graphhopper.util.Helper.createFormatter;
import static com.graphhopper.util.Helper.isEmpty;

/**
 * Custom implementation of internal class GraphHopper uses to parse OSM files into GH's internal graph data structures.
 * In particular, the purpose of this class is to parse and store specific OSM tag information needed to replicate the
 * logic used to create the `lanes` and `flags` columns of R5's street network CSV export.
 *
 * If needed, this class can easily be extended in the future to parse other OSM tag information for use in exporting
 * data about a particular region's GH street network.
 */

public class CustomGraphHopperOSM extends GraphHopper {
    private static final Logger LOG = LoggerFactory.getLogger(CustomGraphHopperOSM.class);

    private String osmPath;
    // Map of OSM Way ID -> (Map of OSM tag name -> tag value)
    private Map<Long, Map<String, String>> osmIdToWayTags;

    private DataAccess nodeMapping;
    private DataAccess artificialIdToOsmNodeIdMapping;
    private DataAccess ghEdgeIdToSegmentIndexMapping;
    private BitUtil bitUtil;

    public CustomGraphHopperOSM(GraphHopperConfig ghConfig) {
        this.osmPath = ghConfig.getString("datareader.file", "");
        this.osmIdToWayTags = Maps.newHashMap();
    }

    @Override
    public boolean load() {
        boolean loaded = super.load();
        GHDirectory dir = new GHDirectory(this.getGraphHopperLocation(), DAType.RAM_STORE);
        bitUtil = BitUtil.LITTLE;
        nodeMapping = dir.create("node_mapping");
        artificialIdToOsmNodeIdMapping = dir.create("artificial_id_mapping");
        ghEdgeIdToSegmentIndexMapping = dir.create("gh_edge_id_to_segment_index");

        if(loaded) {
            nodeMapping.loadExisting();
            artificialIdToOsmNodeIdMapping.loadExisting();
            ghEdgeIdToSegmentIndexMapping.loadExisting();
        }

        return loaded;
    }

    @Override
    protected void flush() {
        super.flush();
        nodeMapping.flush();
        artificialIdToOsmNodeIdMapping.flush();
        ghEdgeIdToSegmentIndexMapping.flush();
    }

    public OsmHelper getOsmHelper(){
        return new OsmHelper(
                nodeMapping,
                artificialIdToOsmNodeIdMapping,
                ghEdgeIdToSegmentIndexMapping,
                bitUtil
        );
    }

    @Override
    protected void importOSM() {
        if (this.getOSMFile() == null)
            throw new IllegalStateException("Couldn't load from existing folder: " + this.getGraphHopperLocation()
                    + " but also cannot use file for DataReader as it wasn't specified!");

        List<CustomArea> customAreas = readCountries();
        if (isEmpty(this.getCustomAreasDirectory())) {
            LOG.info("No custom areas are used, custom_areas.directory not given");
        } else {
            throw new RuntimeException("Custom areas are not currently supported in Replica's Graphhopper instance!");
        }
        AreaIndex<CustomArea> areaIndex = new AreaIndex<>(customAreas);

        if (this.getCountryRuleFactory() == null || this.getCountryRuleFactory().getCountryToRuleMap().isEmpty()) {
            LOG.info("No country rules available");
        } else {
            LOG.info("Applying rules for the following countries: {}", this.getCountryRuleFactory().getCountryToRuleMap().keySet());
        }

        LOG.info("start creating graph from " + this.getOSMFile());
        CustomOsmReader reader = new CustomOsmReader(this.getBaseGraph().getBaseGraph(), this.getOSMParsers(), this.getReaderConfig())
                .setFile(new File(this.getOSMFile()))
                .setAreaIndex(areaIndex)
                .setElevationProvider(this.getElevationProvider())
                .setCountryRuleFactory(this.getCountryRuleFactory());

        createBaseGraphAndProperties();

        try {
            reader.readGraph();
        } catch (IOException ex) {
            throw new RuntimeException("Cannot read file " + getOSMFile(), ex);
        }
        DateFormat f = createFormatter();
        this.getProperties().put("datareader.import.date", f.format(new Date()));
        if (reader.getDataDate() != null)
            this.getProperties().put("datareader.data.date", f.format(reader.getDataDate()));

        writeEncodingManagerToProperties();

        writeOsmNodeIds(reader.getGhNodeIdToOsmNodeIdMap());
        writeArtificialIdMapping(reader.getArtificialIdToOsmNodeIds());
        writeGhEdgeIdToSegmentIndexes(reader.getGhEdgeIdToSegmentIndex());
    }

    public void writeArtificialIdMapping(Map<Long, Long> artificialIdToOsmNodeId) {
        for (long artificialId : artificialIdToOsmNodeId.keySet()) {
            long realId = artificialIdToOsmNodeId.get(artificialId);
            long pointer = 8L * artificialId;
            artificialIdToOsmNodeIdMapping.ensureCapacity(pointer + 8L);
            artificialIdToOsmNodeIdMapping.setInt(pointer, bitUtil.getIntLow(realId));
            artificialIdToOsmNodeIdMapping.setInt(pointer + 4, bitUtil.getIntHigh(realId));
        }
    }

    public void writeOsmNodeIds(Map<Integer, Long> ghToOsmNodeIds) {
        for (int nodeId : ghToOsmNodeIds.keySet()) {
            long osmNodeId = ghToOsmNodeIds.get(nodeId);
            long pointer = 8L * nodeId;
            try {
                nodeMapping.ensureCapacity(pointer + 8L);
            } catch (IllegalArgumentException e) {
                System.out.println("Capacity not there! nodeId: " + nodeId + "; osmNodeId: " + osmNodeId);
                throw e;
            }
            nodeMapping.setInt(pointer, bitUtil.getIntLow(osmNodeId));
            nodeMapping.setInt(pointer + 4, bitUtil.getIntHigh(osmNodeId));
        }
    }

    public void writeGhEdgeIdToSegmentIndexes(Map<Integer, Integer> ghEdgeIdToSegmentIndex) {
        for (int ghEdgeId : ghEdgeIdToSegmentIndex.keySet()) {
            int segmentIndex = ghEdgeIdToSegmentIndex.get(ghEdgeId);
            long pointer = 4L * ghEdgeId;
            ghEdgeIdToSegmentIndexMapping.ensureCapacity(pointer + 4L);
            ghEdgeIdToSegmentIndexMapping.setInt(pointer, segmentIndex);
        }
    }

    public void collectOsmInfo() {
        OsmHelper.collectOsmInfo(osmPath, osmIdToWayTags);
    }

    public Map<Long, Map<String, String>> getOsmIdToWayTags() {
        return osmIdToWayTags;
    }
}

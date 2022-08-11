package com.graphhopper;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.CustomOsmReader;
import com.graphhopper.reader.osm.OSMInput;
import com.graphhopper.reader.osm.OSMInputFile;
import com.graphhopper.routing.util.AllEdgesIterator;
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
import java.util.Set;

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

    // Tags considered by R5 when calculating the value of the `lanes` column
    private static final Set<String> LANE_TAGS = Sets.newHashSet("lanes", "lanes:forward", "lanes:backward");
    private String osmPath;
    // Map of OSM way ID -> (Map of OSM lane tag name -> tag value)
    private Map<Long, Map<String, String>> osmIdToLaneTags;
    // Map of OSM ID to street name. Name is parsed directly from Way, unless name field isn't present,
    // in which case the name is taken from the Relation containing the Way, if one exists
    private Map<Long, String> osmIdToStreetName;
    // Map of OSM ID to highway tag
    private Map<Long, String> osmIdToHighwayTag;
    private DataAccess edgeMapping;
    private DataAccess nodeMapping;
    private DataAccess edgeAdjacentMapping;
    private DataAccess edgeBaseMapping;
    private BitUtil bitUtil;

    public CustomGraphHopperOSM(GraphHopperConfig ghConfig) {
        this.osmPath = ghConfig.getString("datareader.file", "");
        this.osmIdToLaneTags = Maps.newHashMap();
        this.osmIdToStreetName = Maps.newHashMap();
        this.osmIdToHighwayTag = Maps.newHashMap();
        // StableIdEncodedValues.createAndAddEncodedValues(this.getEncodingManagerBuilder());
        // this.getEncodingManagerBuilder().add(new OsmIdTagParser());
        // getEncodingManagerBuilder().add(new IntEncodedValueImpl("osmid", 31, false));
    }

    @Override
    public boolean load() {
        boolean loaded = super.load();
        GHDirectory dir = new GHDirectory(this.getGraphHopperLocation(), DAType.RAM_STORE);
        bitUtil = BitUtil.LITTLE;
        edgeMapping = dir.create("edge_mapping");
        nodeMapping = dir.create("node_mapping");
        edgeAdjacentMapping = dir.create("edge_adjacent_mapping");
        edgeBaseMapping = dir.create("edge_base_mapping");

        if(loaded) {
            edgeMapping.loadExisting();
            nodeMapping.loadExisting();
            edgeAdjacentMapping.loadExisting();
            edgeBaseMapping.loadExisting();
        }

        return loaded;
    }

    @Override
    protected void flush() {
        super.flush();
        edgeMapping.flush();
        nodeMapping.flush();
        edgeAdjacentMapping.flush();
        edgeBaseMapping.flush();
    }

    public OsmHelper getOsmHelper(){
        return new OsmHelper(edgeMapping, nodeMapping,
                edgeAdjacentMapping, edgeBaseMapping, bitUtil,
                getBaseGraph().getEdges());
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
        CustomOsmReader reader = new CustomOsmReader(this.getBaseGraph().getBaseGraph(), this.getEncodingManager(), this.getOSMParsers(), this.getReaderConfig())
                .setFile(_getOSMFile()).
                        setAreaIndex(areaIndex).
                        setElevationProvider(this.getElevationProvider()).
                        setCountryRuleFactory(this.getCountryRuleFactory());

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

        writeOsmNodeIds(reader.getGhNodeIdToOsmNodeIdMap());
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

    // todo: can we move this logic into CustomOsmReader?
    public void collectOsmInfo() {
        LOG.info("Creating custom OSM reader; reading file and parsing lane tag and street name info.");
        List<ReaderRelation> roadRelations = Lists.newArrayList();
        int readCount = 0;
        try (OSMInput input = new OSMInputFile(new File(osmPath)).setWorkerThreads(2).open()) {
            ReaderElement next;
            while((next = input.getNext()) != null) {
                if (next.isType(ReaderElement.WAY)) {
                    if (++readCount % 10000 == 0) {
                        LOG.info("Parsing tag info from OSM ways. " + readCount + " read so far.");
                    }
                    final ReaderWay ghReaderWay = (ReaderWay) next;
                    long osmId = ghReaderWay.getId();

                    // Parse street name from Way, if it exists
                    String wayName = getNameFromOsmElement(ghReaderWay);
                    if (wayName != null) {
                        osmIdToStreetName.put(osmId, wayName);
                    }

                    // Parse highway tag from Way, if it's present
                    String highway = getHighwayFromOsmWay(ghReaderWay);
                    if (highway != null) {
                        osmIdToHighwayTag.put(osmId, highway);
                    }

                    // Parse all tags needed for determining lane counts on edge
                    for (String laneTag : LANE_TAGS) {
                        if (ghReaderWay.hasTag(laneTag)) {
                            if (osmIdToLaneTags.containsKey(osmId)) {
                                Map<String, String> currentLaneTags = osmIdToLaneTags.get(osmId);
                                currentLaneTags.put(laneTag, ghReaderWay.getTag(laneTag));
                                osmIdToLaneTags.put(osmId, currentLaneTags);
                            } else {
                                Map<String, String> newLaneTags = Maps.newHashMap();
                                newLaneTags.put(laneTag, ghReaderWay.getTag(laneTag));
                                osmIdToLaneTags.put(osmId, newLaneTags);
                            }
                        }
                    }
                } else if (next.isType(ReaderElement.RELATION)) {
                    if (next.hasTag("route", "road")) {
                        roadRelations.add((ReaderRelation) next);
                    }
                }
            }
            LOG.info("Finished parsing lane tag info from OSM ways. " + readCount + " total ways were parsed.");

            readCount = 0;
            LOG.info("Scanning road relations to populate street names for Ways that didn't have them set.");
            for (ReaderRelation relation : roadRelations) {
                if (relation.hasTag("route", "road")) {
                    if (++readCount % 1000 == 0) {
                        LOG.info("Parsing tag info from OSM relations. " + readCount + " read so far.");
                    }
                    for (ReaderRelation.Member member : relation.getMembers()) {
                        if (member.getType() == ReaderRelation.Member.WAY) {
                            // If we haven't recorded a street name for a Way in this Relation,
                            // use the Relation's name instead, if it exists
                            if (!osmIdToStreetName.containsKey(member.getRef())) {
                                String streetName = getNameFromOsmElement(relation);
                                if (streetName != null) {
                                    osmIdToStreetName.put(member.getRef(), streetName);
                                }
                            }
                        }
                    }
                }
            }
            LOG.info("Finished scanning road relations for additional street names. " + readCount + " total relations were considered.");
        } catch (Exception e) {
            throw new RuntimeException("Can't open OSM file provided at " + osmPath + "!");
        }
    }

    /**
     * Currently we use this for a few tests where the dataReaderFile is loaded from the classpath
     */
    protected File _getOSMFile() {
        return new File(super.getOSMFile());
    }

    private static String getHighwayFromOsmWay(ReaderWay way) {
        if (way.hasTag("highway")) {
            return way.getTag("highway");
        } else {
            return null;
        }
    }

    private static String getNameFromOsmElement(ReaderElement wayOrRelation) {
        if (wayOrRelation.hasTag("name")) {
            return wayOrRelation.getTag("name");
        } else if (wayOrRelation.hasTag("ref")) {
            return wayOrRelation.getTag("ref");
        } else {
            return null;
        }
    }

    public Map<Long, Map<String, String>> getOsmIdToLaneTags() {
        return osmIdToLaneTags;
    }

    public Map<Integer, Long> getGhIdToOsmId() {
        Map<Integer, Long> ghIdToOsmId = Maps.newHashMap();
        AllEdgesIterator allEdges = getBaseGraph().getAllEdges();
        while (allEdges.next()) {
            // Ignore setting OSM IDs for transit edges, which have a distance of 0
            if (allEdges.getDistance() != 0) {
                int osmid = allEdges.get(getEncodingManager().getIntEncodedValue("osmid"));
                ghIdToOsmId.put(allEdges.getEdge(), (long) osmid);
            }
        }
        return ghIdToOsmId;
    }

    public Map<Long, String> getOsmIdToStreetName() {
        return osmIdToStreetName;
    }

    public Map<Long, String> getOsmIdToHighwayTag() {
        return osmIdToHighwayTag;
    }
}

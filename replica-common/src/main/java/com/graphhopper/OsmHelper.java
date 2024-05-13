package com.graphhopper;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInput;
import com.graphhopper.reader.osm.OSMInputFile;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.util.BitUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class OsmHelper {
    private static final Logger LOG = LoggerFactory.getLogger(OsmHelper.class);

    private DataAccess nodeMapping;
    private DataAccess artificialIdToOsmNodeIdMapping;
    private DataAccess ghEdgeIdToSegmentIndexMapping;
    private BitUtil bitUtil;

    public static final String OSM_NAME_TAG = "name";
    public static final String OSM_HIGHWAY_TAG = "highway";
    public static final String OSM_DIRECTION_TAG = "direction";
    public static final String OSM_LANES_TAG = "lanes";
    public static final String OSM_FORWARD_LANES_TAG = "lanes:forward";
    public static final String OSM_BACKWARD_LANES_TAG = "lanes:backward";
    public static final String OSM_RELATION_ID = "relation";  // Not a formal OSM tag, just a placeholder to hold relation ID for Ways
    public static final String OSM_RELATION_NAME = "relation_name";  // Not a formal OSM tag, just a placeholder to hold relation name for Ways

    // Tags we consider when calculating the value of the `lanes` column
    public static final Set<String> LANE_TAGS = Collections.unmodifiableSet(Sets.newHashSet(OSM_LANES_TAG, OSM_FORWARD_LANES_TAG, OSM_BACKWARD_LANES_TAG));
    // Tags we parse to include as columns in network link export
    public static final Set<String> OTHER_WAY_TAGS = Collections.unmodifiableSet(Sets.newHashSet(OSM_HIGHWAY_TAG, OSM_NAME_TAG, OSM_DIRECTION_TAG));
    public static final Set<String> ALL_WAY_TAGS_TO_PARSE = Collections.unmodifiableSet(Sets.union(LANE_TAGS, OTHER_WAY_TAGS));
    public static final Set<String> ALL_RELATION_TAGS_TO_PARSE = Collections.unmodifiableSet(Sets.newHashSet(OSM_NAME_TAG, OSM_DIRECTION_TAG, OSM_RELATION_ID, OSM_RELATION_NAME));

    public OsmHelper(DataAccess nodeMapping,
                     DataAccess artificialIdToOsmNodeIdMapping,
                     DataAccess ghEdgeIdToSegmentIndexMapping,
                     BitUtil bitUtil) {
        this.nodeMapping = nodeMapping;
        this.artificialIdToOsmNodeIdMapping = artificialIdToOsmNodeIdMapping;
        this.ghEdgeIdToSegmentIndexMapping = ghEdgeIdToSegmentIndexMapping;
        this.bitUtil = bitUtil;
    }

    public long getRealNodeIdFromArtificial(long artificialNodeId) {
        long pointer = 8L * artificialNodeId;
        return bitUtil.toLong(artificialIdToOsmNodeIdMapping.getInt(pointer), artificialIdToOsmNodeIdMapping.getInt(pointer + 4L));
    }

    public long getOSMNode(long internalNodeId) {
        long pointer = 8L * internalNodeId;
        return bitUtil.toLong(nodeMapping.getInt(pointer), nodeMapping.getInt(pointer + 4L));
    }

    public int getSegmentIndexForGhEdge(int ghEdgeId) {
        long pointer = 4L * ghEdgeId;
        return ghEdgeIdToSegmentIndexMapping.getInt(pointer);
    }

    public static Map<String, String> getWayTag(long osmId, Map<Long, Map<String, String>> osmIdToWayTags) {
        return osmIdToWayTags.getOrDefault(osmId, null);
    }

    public static String getTagValueFromOsmElement(ReaderElement wayOrRelation, String tagName) {
        if (wayOrRelation.hasTag(tagName)) {
            return wayOrRelation.getTag(tagName);
        } else {
            return null;
        }
    }

    private static Map<String, String> parseTagsFromOsmElement(ReaderElement wayOrRelation, Set<String> tagsToParse) {
        Map<String, String> parsedTagValues = Maps.newHashMap();

        for (String tag : tagsToParse) {
            if (OSM_NAME_TAG.equals(tag)) {
                // Parse street name, which is a concat of `name` and `ref` tags (if present)
                parsedTagValues.put(tag, getConcatNameFromOsmElement(wayOrRelation));
            } else if (OSM_RELATION_ID.equals(tag)) {  // special case, OSM_RELATION_ID isn't a real tag
                parsedTagValues.put(tag, Long.toString(wayOrRelation.getId()));
            } else if (OSM_RELATION_NAME.equals(tag)) {  // special case, OSM_RELATION_NAME isn't a real tag
                parsedTagValues.put(tag, getTagValueFromOsmElement(wayOrRelation, "name"));
            }
            else {
                parsedTagValues.put(tag, getTagValueFromOsmElement(wayOrRelation, tag));
            }
        }

        // Remove any tags that weren't present for this element (ie the value was parsed as null)
        parsedTagValues.values().removeIf(Objects::isNull);
        return parsedTagValues;
    }

    public static Map<String, String> parseTagsFromOsmWay(ReaderWay way) {
        return parseTagsFromOsmElement(way, ALL_WAY_TAGS_TO_PARSE);
    }

    public static Map<String, String> parseTagsFromOsmRelation(ReaderRelation relation, Set<String> tagsToParse) {
        return parseTagsFromOsmElement(relation, tagsToParse);
    }

    // if only `name` or only `ref` tag exist, return that. if both exist, return "<ref>, <name>". else, return null
    public static String getConcatNameFromOsmElement(ReaderElement wayOrRelation) {
        String name = getTagValueFromOsmElement(wayOrRelation, "name");
        if (wayOrRelation.hasTag("ref")) {
            name = name == null ? wayOrRelation.getTag("ref") : wayOrRelation.getTag("ref") + ", " + name;
        }
        return name;
    }

    public static void updateOsmIdToWayTags(Map<Long, Map<String, String>> osmIdToWayTags, Long osmId, Map<String, String> newTagValues) {
        Map<String, String> currentWayTags = new HashMap<>(osmIdToWayTags.getOrDefault(osmId, Maps.newHashMap()));
        for (String tag : newTagValues.keySet()) {
            if (currentWayTags.containsKey(tag)) {
                throw new RuntimeException("Value for tag " + tag + " has already been stored! Only new tag values not already in osmIdToWayTags allowed");
            } else {
                currentWayTags.put(tag, newTagValues.get(tag));
            }
        }
        osmIdToWayTags.put(osmId, currentWayTags);
    }

    // todo: can we move this logic into CustomOsmReader?
    // todo: not all of the info we parse here is relevant for the server - can we stop parsing it in CustomGraphHopperGtfs?
    public static void collectOsmInfo(String osmPath, Map<Long, Map<String, String>> osmIdToWayTags) {
        LOG.info("Creating custom OSM reader; reading file and parsing lane tag and street name info.");
        List<ReaderRelation> roadRelations = Lists.newArrayList();
        int readCount = 0;
        try (OSMInput input = new OSMInputFile(new File(osmPath)).setWorkerThreads(2).open()) {
            ReaderElement next;
            while((next = input.getNext()) != null) {
                if (next.getType().equals(ReaderElement.Type.WAY)) {
                    if (++readCount % 100_000 == 0) {
                        LOG.info("Parsing tag info from OSM ways. " + readCount + " read so far.");
                    }
                    final ReaderWay ghReaderWay = (ReaderWay) next;
                    // Parse highway, name, and lane tags from Way
                    updateOsmIdToWayTags(osmIdToWayTags, ghReaderWay.getId(), parseTagsFromOsmWay(ghReaderWay));
                } else if (next.getType().equals(ReaderElement.Type.RELATION)) {
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
                        if (member.getType() == ReaderElement.Type.WAY) {
                            // Out of all possible relation tags we could parse, narrow down to set
                            // that we haven't yet parsed values for
                            Set<String> relationTagsToParse = Sets.newHashSet(ALL_RELATION_TAGS_TO_PARSE);
                            relationTagsToParse.removeIf(tag -> osmIdToWayTags.containsKey(member.getRef())
                                            && osmIdToWayTags.get(member.getRef()).containsKey(tag));

                            updateOsmIdToWayTags(osmIdToWayTags, member.getRef(), parseTagsFromOsmRelation(relation, relationTagsToParse));
                        }
                    }
                }
            }
            LOG.info("Finished scanning road relations for additional street names. " + readCount + " total relations were considered.");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Can't open OSM file provided at " + osmPath + "!");
        }
    }
}

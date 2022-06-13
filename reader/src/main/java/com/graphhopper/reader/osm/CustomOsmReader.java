package com.graphhopper.reader.osm;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongIndexedContainer;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CustomOsmReader extends OSMReader {
    private static final BitUtil bitUtil = BitUtil.LITTLE;
    private final DataAccess nodeMapping;
    private final DataAccess edgeMapping;
    private final DataAccess edgeAdjacentMapping;
    private final DataAccess edgeBaseMapping;
    private final EncodingManager encodingManager;
    private final DistanceCalc distCalc = DistanceCalcEarth.DIST_EARTH;
    private final NodeAccess nodeAccess;

    public CustomOsmReader(GraphHopperStorage ghStorage) {
        super(ghStorage);
        Directory dir = ghStorage.getDirectory();
        nodeMapping = dir.find("node_mapping");
        nodeMapping.create(2000);
        edgeMapping = dir.find("edge_mapping");
        edgeMapping.create(1000);
        edgeAdjacentMapping = dir.find("edge_adjacent_mapping");
        edgeAdjacentMapping.create(1000);
        edgeBaseMapping = dir.find("edge_base_mapping");
        edgeBaseMapping.create(1000);
        this.encodingManager = ghStorage.getEncodingManager();
        this.nodeAccess = ghStorage.getNodeAccess();
    }

    protected void storeOsmNodeID(int nodeId, long osmNodeId) {
        long pointer = 8L * nodeId;
        // System.out.println("stored OSM node id: " + nodeId + " ; " + osmNodeId);
        nodeMapping.ensureCapacity(pointer + 8L);
        nodeMapping.setInt(pointer, bitUtil.getIntLow(osmNodeId));
        nodeMapping.setInt(pointer + 4, bitUtil.getIntHigh(osmNodeId));
    }

    @Override
    protected void storeOsmWayID(int edgeId, long osmWayId) {
        super.storeOsmWayID(edgeId, osmWayId);
        // System.out.println("Added edge with ID " + edgeId + " and with osm way ID " + osmWayId);

        long pointer = 8L * edgeId;
        edgeMapping.ensureCapacity(pointer + 8L);
        edgeMapping.setInt(pointer, bitUtil.getIntLow(osmWayId));
        edgeMapping.setInt(pointer + 4, bitUtil.getIntHigh(osmWayId));
    }

    protected void storeAdjacentNode(int edgeId, int adjacentNodeId) {
        long pointer = 8L * edgeId;
        // System.out.println("storing edge " + edgeId + " with adjacent node " + adjacentNodeId);

        edgeAdjacentMapping.ensureCapacity(pointer + 8L);
        edgeAdjacentMapping.setInt(pointer, bitUtil.getIntLow(adjacentNodeId));
        edgeAdjacentMapping.setInt(pointer + 4, bitUtil.getIntHigh(adjacentNodeId));
    }

    protected void storeBaseNode(int edgeId, int adjacentNodeId) {
        long pointer = 8L * edgeId;
        // System.out.println("storing edge " + edgeId + " with base node " + adjacentNodeId);

        edgeBaseMapping.ensureCapacity(pointer + 8L);
        edgeBaseMapping.setInt(pointer, bitUtil.getIntLow(adjacentNodeId));
        edgeBaseMapping.setInt(pointer + 4, bitUtil.getIntHigh(adjacentNodeId));
    }

    @Override
    protected void processWay(ReaderWay way) {
        if (way.getNodes().size() < 2)
            return;

        // ignore multipolygon geometry
        if (!way.hasTags())
            return;

        long wayOsmId = way.getId();
        // System.out.println("processing way " + wayOsmId);

        EncodingManager.AcceptWay acceptWay = new EncodingManager.AcceptWay();
        if (!encodingManager.acceptWay(way, acceptWay))
            return;

        IntsRef relationFlags = getRelFlagsMap(way.getId());

        // TODO move this after we have created the edge and know the coordinates => encodingManager.applyWayTags
        LongArrayList osmNodeIds = way.getNodes();
        // Estimate length of ways containing a route tag e.g. for ferry speed calculation
        int first = getNodeMap().get(osmNodeIds.get(0));
        int last = getNodeMap().get(osmNodeIds.get(osmNodeIds.size() - 1));
        double firstLat = getTmpLatitude(first), firstLon = getTmpLongitude(first);
        double lastLat = getTmpLatitude(last), lastLon = getTmpLongitude(last);
        if (!Double.isNaN(firstLat) && !Double.isNaN(firstLon) && !Double.isNaN(lastLat) && !Double.isNaN(lastLon)) {
            double estimatedDist = distCalc.calcDist(firstLat, firstLon, lastLat, lastLon);
            // Add artificial tag for the estimated distance and center
            way.setTag("estimated_distance", estimatedDist);
            way.setTag("estimated_center", new GHPoint((firstLat + lastLat) / 2, (firstLon + lastLon) / 2));
        }

        if (way.getTag("duration") != null) {
            try {
                long dur = OSMReaderUtility.parseDuration(way.getTag("duration"));
                // Provide the duration value in seconds in an artificial graphhopper specific tag:
                way.setTag("duration:seconds", Long.toString(dur));
            } catch (Exception ex) {
                System.out.println("Parsing error in way with OSMID=" + way.getId() + " : " + ex.getMessage());
            }
        }

        IntsRef edgeFlags = encodingManager.handleWayTags(way, acceptWay, relationFlags);
        if (edgeFlags.isEmpty())
            return;

        List<EdgeIteratorState> createdEdges = new ArrayList<>();
        // look for barriers along the way
        final int size = osmNodeIds.size();
        int lastBarrier = -1;
        LongArrayList nonBarrierNodeIds = new LongArrayList();
        for (int i = 0; i < size; i++) {
            long nodeId = osmNodeIds.get(i);
            long nodeFlags = getNodeFlagsMap().get(nodeId);
            // barrier was spotted and the way is passable for that mode of travel
            if (nodeFlags > 0) {
                // System.out.println("barrier spotted for way id " + wayOsmId);
                if (isOnePassable(encodingManager.getAccessEncFromNodeFlags(nodeFlags), edgeFlags)) {
                    // remove barrier to avoid duplicates
                    getNodeFlagsMap().put(nodeId, 0);

                    // create shadow node copy for zero length edge
                    long newNodeId = addBarrierNode(nodeId);
                    // System.out.println("Added barrier node with nodeId " + nodeId + " and newNodeId " + newNodeId);
                    if (i > 0) {
                        // start at beginning of array if there was no previous barrier
                        if (lastBarrier < 0)
                            lastBarrier = 0;

                        // add way up to barrier shadow node
                        int length = i - lastBarrier + 1;
                        LongArrayList partNodeIds = new LongArrayList();
                        partNodeIds.add(osmNodeIds.buffer, lastBarrier, length);
                        partNodeIds.set(length - 1, newNodeId);
                        createdEdges.addAll(addOSMWay(partNodeIds, edgeFlags, wayOsmId));

                        // create zero length edge for barrier
                        createdEdges.addAll(addBarrierEdge(newNodeId, nodeId, edgeFlags, nodeFlags, wayOsmId));
                        // System.out.println("[if] Added barrier edge " + newNodeId + " : " + nodeFlags + " with way ID " + wayOsmId);
                    } else {
                        // run edge from real first node to shadow node
                        createdEdges.addAll(addBarrierEdge(nodeId, newNodeId, edgeFlags, nodeFlags, wayOsmId));
                        // System.out.println("[else] Added barrier edge " + newNodeId + " : " + nodeFlags + " with way ID " + wayOsmId);
                        // exchange first node for created barrier node
                        osmNodeIds.set(0, newNodeId);
                    }
                    // remember barrier for processing the way behind it
                    lastBarrier = i;
                }
            }
        }

        // just add remainder of way to graph if barrier was not the last node
        if (lastBarrier >= 0) {
            if (lastBarrier < size - 1) {
                LongArrayList partNodeIds = new LongArrayList();
                partNodeIds.add(osmNodeIds.buffer, lastBarrier, size - lastBarrier);
                createdEdges.addAll(addOSMWay(partNodeIds, edgeFlags, wayOsmId));
            }
        } else {
            // no barriers - simply add the whole way
            createdEdges.addAll(addOSMWay(way.getNodes(), edgeFlags, wayOsmId));
        }

        for (EdgeIteratorState edge : createdEdges) {
            encodingManager.applyWayTags(way, edge);
        }
    }

    @Override
    Collection<EdgeIteratorState> addBarrierEdge(long fromId, long toId, IntsRef inEdgeFlags, long nodeFlags, long wayOsmId) {
        IntsRef edgeFlags = IntsRef.deepCopyOf(inEdgeFlags);
        // clear blocked directions from flags
        for (BooleanEncodedValue accessEnc : encodingManager.getAccessEncFromNodeFlags(nodeFlags)) {
            accessEnc.setBool(false, edgeFlags, false);
            accessEnc.setBool(true, edgeFlags, false);
        }
        // add edge
        LongIndexedContainer barrierNodeIds = new LongArrayList();
        barrierNodeIds.add(fromId);
        barrierNodeIds.add(toId);
        // System.out.println("Adding barrier from " + fromId + " to " + toId + " with wayOsmId " + wayOsmId);
        return addOSMWay(barrierNodeIds, edgeFlags, wayOsmId);
    }

    Collection<EdgeIteratorState> addOSMWay(final LongIndexedContainer osmNodeIds, final IntsRef flags, final long wayOsmId) {
        // System.out.println("Adding way " + wayOsmId + " with osm node IDs " + Arrays.toString(osmNodeIds.toArray()));
        PointList pointList = new PointList(osmNodeIds.size(), nodeAccess.is3D());
        List<EdgeIteratorState> newEdges = new ArrayList<>(5);
        int firstNode = -1;
        int lastIndex = osmNodeIds.size() - 1;
        int lastInBoundsPillarNode = -1;
        long prevOsmNodeId = -1;
        try {
            for (int i = 0; i < osmNodeIds.size(); i++) {
                long osmNodeId = osmNodeIds.get(i);
                if (prevOsmNodeId < 0) {
                    prevOsmNodeId = osmNodeId;
                }
                int tmpNode = getNodeMap().get(osmNodeId);
                if (tmpNode == EMPTY_NODE)
                    continue;

                // skip osmIds with no associated pillar or tower id (e.g. !OSMReader.isBounds)
                if (tmpNode == TOWER_NODE)
                    continue;

                if (tmpNode == PILLAR_NODE) {
                    // In some cases no node information is saved for the specified osmId.
                    // ie. a way references a <node> which does not exist in the current file.
                    // => if the node before was a pillar node then convert into to tower node (as it is also end-standing).
                    if (!pointList.isEmpty() && lastInBoundsPillarNode > -TOWER_NODE) {
                        // transform the pillar node to a tower node
                        tmpNode = lastInBoundsPillarNode;
                        tmpNode = handlePillarNode(tmpNode, osmNodeId, null, true);
                        tmpNode = -tmpNode - 3;
                        if (pointList.getSize() > 1 && firstNode >= 0) {
                            // TOWER node
                            EdgeIteratorState newEdge = addEdge(firstNode, tmpNode, pointList, flags, wayOsmId);
                            newEdges.add(newEdge);
                            // System.out.println("[1] Added edge from " + firstNode + " to " + tmpNode + " with way ID " + wayOsmId + " and node IDs " + prevOsmNodeId + " -> " + osmNodeId);
                            if (prevOsmNodeId > 0) {
                                storeOsmNodeID(firstNode, prevOsmNodeId);
                            }
                            if (osmNodeId > 0) {
                                storeOsmNodeID(tmpNode, osmNodeId);
                            }
                            storeBaseNode(newEdge.getEdge(), firstNode);
                            storeAdjacentNode(newEdge.getEdge(), tmpNode);
                            prevOsmNodeId = osmNodeId;
                            pointList.clear();
                            pointList.add(nodeAccess, tmpNode);
                        }
                        firstNode = tmpNode;
                        lastInBoundsPillarNode = -1;
                    }
                    continue;
                }

                if (tmpNode <= -TOWER_NODE && tmpNode >= TOWER_NODE)
                    throw new AssertionError("Mapped index not in correct bounds " + tmpNode + ", " + osmNodeId);

                if (tmpNode > -TOWER_NODE) {
                    boolean convertToTowerNode = i == 0 || i == lastIndex;
                    if (!convertToTowerNode) {
                        lastInBoundsPillarNode = tmpNode;
                    }

                    // PILLAR node, but convert to towerNode if end-standing
                    tmpNode = handlePillarNode(tmpNode, osmNodeId, pointList, convertToTowerNode);
                }

                if (tmpNode < TOWER_NODE) {
                    // TOWER node
                    tmpNode = -tmpNode - 3;

                    if (firstNode >= 0 && firstNode == tmpNode) {
                        // loop detected. See #1525 and #1533. Insert last OSM ID as tower node. Do this for all loops so that users can manipulate loops later arbitrarily.
                        long lastOsmNodeId = osmNodeIds.get(i - 1);
                        int lastGHNodeId = getNodeMap().get(lastOsmNodeId);
                        if (lastGHNodeId < TOWER_NODE) {
                            System.out.println("Pillar node " + lastOsmNodeId + " is already a tower node and used in loop, see #1533. " +
                                    "Fix mapping for way " + wayOsmId + ", nodes:" + osmNodeIds);
                            break;
                        }

                        int newEndNode = -handlePillarNode(lastGHNodeId, lastOsmNodeId, pointList, true) - 3;
                        EdgeIteratorState newEdge = addEdge(firstNode, newEndNode, pointList, flags, wayOsmId);
                        newEdges.add(newEdge);
                        // System.out.println("[2] Added edge from " + firstNode + " to " + newEndNode + " with way ID " + wayOsmId + " and node IDs " + prevOsmNodeId + " -> " + osmNodeId);
                        if (prevOsmNodeId > 0) {
                            storeOsmNodeID(firstNode, prevOsmNodeId);
                        }
                        if (osmNodeId > 0) {
                            storeOsmNodeID(newEndNode, osmNodeId);
                        }
                        storeBaseNode(newEdge.getEdge(), firstNode);
                        storeAdjacentNode(newEdge.getEdge(), newEndNode);
                        prevOsmNodeId = osmNodeId;
                        pointList.clear();
                        pointList.add(nodeAccess, newEndNode);
                        firstNode = newEndNode;
                    }

                    pointList.add(nodeAccess, tmpNode);
                    if (firstNode >= 0) {
                        EdgeIteratorState newEdge = addEdge(firstNode, tmpNode, pointList, flags, wayOsmId);
                        newEdges.add(newEdge);
                        // System.out.println("[3] Added edge from " + firstNode + " to " + tmpNode + " with way ID " + wayOsmId + " and node IDs " + prevOsmNodeId + " -> " + osmNodeId);
                        if (prevOsmNodeId > 0) {
                            storeOsmNodeID(firstNode, prevOsmNodeId);
                        }
                        if (osmNodeId > 0) {
                            storeOsmNodeID(tmpNode, osmNodeId);
                        }
                        storeBaseNode(newEdge.getEdge(), firstNode);
                        storeAdjacentNode(newEdge.getEdge(), tmpNode);
                        prevOsmNodeId = osmNodeId;
                        pointList.clear();
                        pointList.add(nodeAccess, tmpNode);
                    }
                    firstNode = tmpNode;
                }
            }
        } catch (RuntimeException ex) {
            System.out.println("Couldn't properly add edge with osm ids:" + osmNodeIds);
            throw ex;
        }
        System.out.println("Done adding edges for way " + wayOsmId);
        return newEdges;
    }

    private int handlePillarNode(int tmpNode, long osmId, PointList pointList, boolean convertToTowerNode) {
        tmpNode = tmpNode - 3;
        double lat = pillarInfo.getLatitude(tmpNode);
        double lon = pillarInfo.getLongitude(tmpNode);
        double ele = pillarInfo.getElevation(tmpNode);
        if (lat == Double.MAX_VALUE || lon == Double.MAX_VALUE)
            throw new RuntimeException("Conversion pillarNode to towerNode already happened!? "
                    + "osmId:" + osmId + " pillarIndex:" + tmpNode);

        if (convertToTowerNode) {
            // convert pillarNode type to towerNode, make pillar values invalid
            pillarInfo.setNode(tmpNode, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
            tmpNode = addTowerNode(osmId, lat, lon, ele);
        } else if (pointList.is3D())
            pointList.add(lat, lon, ele);
        else
            pointList.add(lat, lon);

        return tmpNode;
    }

    private static boolean isOnePassable(List<BooleanEncodedValue> checkEncoders, IntsRef edgeFlags) {
        for (BooleanEncodedValue accessEnc : checkEncoders) {
            if (accessEnc.getBool(false, edgeFlags) || accessEnc.getBool(true, edgeFlags))
                return true;
        }
        return false;
    }
}

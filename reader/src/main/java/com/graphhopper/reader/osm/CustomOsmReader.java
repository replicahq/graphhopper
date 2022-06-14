package com.graphhopper.reader.osm;

import com.carrotsearch.hppc.LongIndexedContainer;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CustomOsmReader extends OSMReader {
    private static final BitUtil bitUtil = BitUtil.LITTLE;
    private final DataAccess nodeMapping;
    private final DataAccess edgeMapping;
    private final DataAccess edgeAdjacentMapping;
    private final DataAccess edgeBaseMapping;
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
        this.nodeAccess = ghStorage.getNodeAccess();
    }

    protected void storeOsmNodeID(int nodeId, long osmNodeId) {
        long pointer = 8L * nodeId;
        nodeMapping.ensureCapacity(pointer + 8L);
        nodeMapping.setInt(pointer, bitUtil.getIntLow(osmNodeId));
        nodeMapping.setInt(pointer + 4, bitUtil.getIntHigh(osmNodeId));
    }

    @Override
    protected void storeOsmWayID(int edgeId, long osmWayId) {
        super.storeOsmWayID(edgeId, osmWayId);
        long pointer = 8L * edgeId;
        edgeMapping.ensureCapacity(pointer + 8L);
        edgeMapping.setInt(pointer, bitUtil.getIntLow(osmWayId));
        edgeMapping.setInt(pointer + 4, bitUtil.getIntHigh(osmWayId));
    }

    protected void storeAdjacentNode(int edgeId, int adjacentNodeId) {
        long pointer = 8L * edgeId;
        edgeAdjacentMapping.ensureCapacity(pointer + 8L);
        edgeAdjacentMapping.setInt(pointer, bitUtil.getIntLow(adjacentNodeId));
        edgeAdjacentMapping.setInt(pointer + 4, bitUtil.getIntHigh(adjacentNodeId));
    }

    protected void storeBaseNode(int edgeId, int adjacentNodeId) {
        long pointer = 8L * edgeId;
        edgeBaseMapping.ensureCapacity(pointer + 8L);
        edgeBaseMapping.setInt(pointer, bitUtil.getIntLow(adjacentNodeId));
        edgeBaseMapping.setInt(pointer + 4, bitUtil.getIntHigh(adjacentNodeId));
    }

    /*
    Copy of OSMReader.addOsmWay() that collects additional information about OSM node IDs
    and the base/adjacent graphhopper nodes associated with each new graphhopper edge
    */
    @Override
    Collection<EdgeIteratorState> addOSMWay(final LongIndexedContainer osmNodeIds, final IntsRef flags, final long wayOsmId) {
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

    /*
    Exact copy of OSMReader.handlePillarNode() (because it's private in that class)
    */
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
}

package com.graphhopper.reader.osm;

import com.graphhopper.reader.ReaderNode;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.BitUtil;

public class CustomOsmReader extends OSMReader {
    private static final BitUtil bitUtil = BitUtil.LITTLE;
    private final DataAccess nodeMapping;
    private final DataAccess edgeMapping;

    public CustomOsmReader(GraphHopperStorage ghStorage) {
        super(ghStorage);
        Directory dir = ghStorage.getDirectory();
        nodeMapping = dir.find("node_mapping");
        nodeMapping.create(2000);
        edgeMapping = dir.find("edge_mapping");
        edgeMapping.create(1000);
    }

    @Override
    boolean addNode(ReaderNode node) {
        boolean result = super.addNode(node);
        System.out.println("nodeId " + node.getId());
        if (result) {
            int internalNodeId = this.getNodeMap().get(node.getId());

            // if internalNodeId < -2 then this is a tower node
            if (internalNodeId < -2) {
                storeOsmNodeID(internalNodeId, node.getId());
                System.out.println("added");
            } else {
                System.out.println("skipped! internalNodeId: " + internalNodeId);
            }
        }
        else {
            System.out.println("Result is false! nodeId: " + node.getId());
        }
        return result;
    }

    protected void storeOsmNodeID(int nodeId, long osmNodeId) {
        // assuming nodeId < -2, meaning it's a tower node
        nodeId = -nodeId;

        // Not sure why the node process adds 3 to the node id?
        // Possibly as tower and pillar node are internally stored in the same map,
        // The -3 removes the conflict where id == 0, which would result in tower == -0, pillar == 0
        nodeId -= 3;
        long pointer = 8L * nodeId;
        System.out.println("set pointer: " + pointer + " ; " + nodeId);
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
}

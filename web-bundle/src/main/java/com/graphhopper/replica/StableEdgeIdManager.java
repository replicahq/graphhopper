package com.graphhopper.replica;

import com.graphhopper.GraphHopper;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.stableid.StableIdEncodedValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StableEdgeIdManager {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;
    private final OsmHelper osmHelper;

    public StableEdgeIdManager(GraphHopper graphHopper, OsmHelper osmHelper) {
        this.graphHopper = graphHopper;
        this.osmHelper = osmHelper;
    }

    public void setStableEdgeIds() {
        AllEdgesIterator edgesIterator = graphHopper.getBaseGraph().getAllEdges();
        EncodingManager encodingManager = graphHopper.getEncodingManager();

        StableIdEncodedValues stableIdEncodedValues = StableIdEncodedValues.fromEncodingManager(encodingManager, osmHelper);

        // Set both forward and reverse stable edge IDs for each edge
        int assignedIdCount = 0;
        while (edgesIterator.next()) {
            // Ignore setting stable IDs for transit edges, which have a distance of 0
            if (edgesIterator.getDistance() != 0) {
                stableIdEncodedValues.setStableId(true, edgesIterator);
                stableIdEncodedValues.setStableId(false, edgesIterator);
                assignedIdCount++;
            }
        }
        graphHopper.getBaseGraph().flush();
        logger.info("Total number of bidirectional edges assigned with stable edge IDs: " + assignedIdCount);
    }
}

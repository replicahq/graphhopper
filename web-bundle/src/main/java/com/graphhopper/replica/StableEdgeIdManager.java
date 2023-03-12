package com.graphhopper.replica;

import com.graphhopper.GraphHopper;
import com.graphhopper.OsmHelper;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.stableid.StableIdEncodedValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class StableEdgeIdManager {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;
    private final OsmHelper osmHelper;
    private final Map<Long, String> osmIdToHighway;

    public StableEdgeIdManager(GraphHopper graphHopper, OsmHelper osmHelper, Map<Long, String> osmIdToHighway) {
        this.graphHopper = graphHopper;
        this.osmHelper = osmHelper;
        this.osmIdToHighway = osmIdToHighway;
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
                stableIdEncodedValues.setStableId(true, edgesIterator, osmIdToHighway);
                stableIdEncodedValues.setStableId(false, edgesIterator, osmIdToHighway);
                assignedIdCount++;
            }
        }
        graphHopper.getBaseGraph().flush();
        logger.info("Total number of bidirectional edges assigned with stable edge IDs: " + assignedIdCount);
    }
}

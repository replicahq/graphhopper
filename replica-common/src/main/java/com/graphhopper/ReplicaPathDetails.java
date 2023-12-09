package com.graphhopper;

import com.graphhopper.RouterConstants;
import com.graphhopper.util.Parameters;


public final class ReplicaPathDetails {
    private ReplicaPathDetails() {
        // utility class
    }

    public static final String SPEED = Parameters.Details.AVERAGE_SPEED;
    public static final String TIME = Parameters.Details.TIME;
    public static final String STABLE_EDGE_IDS = "stable_edge_ids";
    // detail is derived from the encoded value (see PathDetailsBuilderFactoryWithStableId), so the two should be kept in sync
    public static final String OSM_ID = RouterConstants.OSM_ID_ENCODED_VALUE;
}

package com.graphhopper;

import com.google.common.collect.Sets;

import java.util.Set;

public final class RouterConstants {

    private RouterConstants() {
        // utility class
    }

    public static final String CAR_VEHICLE_NAME = "car";
    public static final String TRUCK_VEHICLE_NAME = "truck";
    public static final String SMALL_TRUCK_VEHICLE_NAME = "small_truck";

    public static final Set<Integer> STREET_BASED_ROUTE_TYPES = Sets.newHashSet(0, 3, 5);

    public static final String OSM_ID_ENCODED_VALUE = "osmid";
    public static final String OSM_ID_PATH_DETAIL = OSM_ID_ENCODED_VALUE;
}

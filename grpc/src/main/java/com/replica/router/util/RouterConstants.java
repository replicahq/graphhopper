package com.replica.router.util;

import com.google.common.collect.Sets;

import java.util.Set;

public final class RouterConstants {
    private RouterConstants() {
        // utility class
    }

    public static final Set<Integer> STREET_BASED_ROUTE_TYPES = Sets.newHashSet(0, 3, 5);
}

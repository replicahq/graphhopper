package com.replica.metrics;

import com.google.common.collect.Lists;
import com.timgroup.statsd.StatsDClient;

import java.util.List;

public class MetricsHelper {

    // If a region name has been set, add it to tag list
    public static String[] applyRegionName(String[] tags, String regionName) {
        if (regionName == null) {
            return tags;
        } else {
            List<String> newTags = Lists.newArrayList(tags);
            newTags.add("replica_region:" + regionName);
            return newTags.toArray(new String[newTags.size()]);
        }
    }

    public static void sendDatadogStats(StatsDClient statsDClient, String[] tags, double durationSeconds) {
        if (statsDClient != null) {
            statsDClient.incrementCounter("routers.num_requests", tags);
            statsDClient.distribution("routers.request_seconds", durationSeconds, tags);
        }
    }
}

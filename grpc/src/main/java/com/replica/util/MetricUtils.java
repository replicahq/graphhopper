package com.replica.util;

import com.google.common.collect.Lists;
import com.timgroup.statsd.StatsDClient;

import java.util.List;
import java.util.Map;

public final class MetricUtils {

    private MetricUtils () {
        // utility class
    }

    public static void sendDatadogStats(StatsDClient statsDClient, String[] tags, double durationSeconds) {
        if (statsDClient != null) {
            statsDClient.incrementCounter("routers.num_requests", tags);
            statsDClient.distribution("routers.request_seconds", durationSeconds, tags);
        }
    }

    // Apply region + helm release tags, if they exist
    public static String[] applyCustomTags(String[] tags, Map<String, String> customTags) {
        for (String tagName : customTags.keySet()) {
            tags = applyTag(tags, tagName, customTags.get(tagName));
        }
        return tags;
    }

    private static String[] applyTag(String[] tags, String tagName, String tagValue) {
        if (tagValue == null) {
            return tags;
        } else {
            List<String> newTags = Lists.newArrayList(tags);
            newTags.add(tagName + ":" + tagValue);
            return newTags.toArray(new String[newTags.size()]);
        }
    }
}
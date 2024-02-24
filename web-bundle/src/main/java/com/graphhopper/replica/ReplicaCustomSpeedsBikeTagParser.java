package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.BikeAverageSpeedParser;
import com.graphhopper.util.PMap;

public class ReplicaCustomSpeedsBikeTagParser extends BikeAverageSpeedParser {
    private final ImmutableMap<Long, Double> osmWayIdToMaxSpeed;

    public ReplicaCustomSpeedsBikeTagParser(EncodedValueLookup lookup, PMap configuration, ImmutableMap<Long, Double> osmWayIdToMaxSpeed) {
        super(lookup, configuration);
        this.osmWayIdToMaxSpeed = osmWayIdToMaxSpeed;
    }

    @Override
    public double applyMaxSpeed(ReaderWay way, double speed, boolean bwd) {
        return CustomSpeedsUtils.getCustomMaxSpeed(way, osmWayIdToMaxSpeed).orElseGet(() -> super.applyMaxSpeed(way, speed, bwd));
    }
}

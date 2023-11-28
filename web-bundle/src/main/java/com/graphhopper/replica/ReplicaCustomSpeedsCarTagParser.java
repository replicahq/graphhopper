package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.CarAverageSpeedParser;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.util.PMap;

import java.util.Objects;

public class ReplicaCustomSpeedsCarTagParser extends CarAverageSpeedParser {
    private final ImmutableMap<Long, Double> osmWayIdToMaxSpeed;

    public ReplicaCustomSpeedsCarTagParser(EncodedValueLookup lookup, PMap configuration, ImmutableMap<Long, Double> osmWayIdToMaxSpeed) {
        super(lookup, configuration);
        this.osmWayIdToMaxSpeed = osmWayIdToMaxSpeed;
    }

    @Override
    protected double applyMaxSpeed(ReaderWay way, double speed, boolean bwd) {
        return CustomSpeedsUtils.getCustomMaxSpeed(way, osmWayIdToMaxSpeed).orElseGet(() -> super.applyMaxSpeed(way, speed, bwd));
    }

    @Override
    protected double applyBadSurfaceSpeed(ReaderWay way, double speed) {
        return CustomSpeedsUtils.getCustomBadSurfaceSpeed(way, osmWayIdToMaxSpeed).orElseGet(() -> super.applyBadSurfaceSpeed(way, speed));
    }
}

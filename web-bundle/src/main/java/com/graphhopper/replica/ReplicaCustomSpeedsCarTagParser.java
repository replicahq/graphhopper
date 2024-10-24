package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.CarAverageSpeedParser;
import com.graphhopper.util.PMap;
import org.apache.commons.lang3.tuple.Pair;

public class ReplicaCustomSpeedsCarTagParser extends CarAverageSpeedParser {
    private final ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed;

    public ReplicaCustomSpeedsCarTagParser(EncodedValueLookup lookup, PMap configuration, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed) {
        super(lookup, configuration);
        this.osmWayIdAndBwdToMaxSpeed = osmWayIdAndBwdToMaxSpeed;
    }

    @Override
    protected double applyMaxSpeed(ReaderWay way, double speed, boolean bwd) {
        return CustomSpeedsUtils.getCustomMaxSpeed(way, osmWayIdAndBwdToMaxSpeed, bwd).orElseGet(() -> super.applyMaxSpeed(way, speed, bwd));
    }
}

package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.CarAverageSpeedParser;
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
        // n.b. superclass logic sets max speed to be 90% of the OSM's max speed for the way, but we don't apply the 90%
        // discount for the custom speeds we've been explicitly given
        Double knownMaxSpeed = osmWayIdToMaxSpeed.get(way.getId());
        return Objects.requireNonNullElseGet(knownMaxSpeed, () -> super.applyMaxSpeed(way, speed, bwd));
    }

    @Override
    protected double applyBadSurfaceSpeed(ReaderWay way, double speed) {
        // if we've been explicitly given a custom speed to use for the way, we should not apply any additional logic
        // for bad road surfaces
        if (osmWayIdToMaxSpeed.containsKey(way.getId())) {
            return speed;
        }

        return super.applyBadSurfaceSpeed(way, speed);
    }
}

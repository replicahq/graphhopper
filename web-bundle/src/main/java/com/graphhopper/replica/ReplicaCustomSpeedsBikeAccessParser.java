package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.BikeAccessParser;
import com.graphhopper.util.PMap;
import org.apache.commons.lang3.tuple.Pair;

import static com.graphhopper.customspeeds.CustomSpeedsUtils.validateCustomSpeedDirection;

public class ReplicaCustomSpeedsBikeAccessParser extends BikeAccessParser {
    private final ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed;
    private final boolean bwdColumnPresent;

    public ReplicaCustomSpeedsBikeAccessParser(EncodedValueLookup lookup, PMap properties, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed, boolean bwdColumnPresent) {
        super(lookup, properties);
        this.osmWayIdAndBwdToMaxSpeed = osmWayIdAndBwdToMaxSpeed;
        this.bwdColumnPresent = bwdColumnPresent;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
        super.handleWayTags(edgeId, edgeIntAccess, way);
        validateCustomSpeedDirection(osmWayIdAndBwdToMaxSpeed, bwdColumnPresent, way.getId(), accessEnc, edgeId, edgeIntAccess);
    }
}

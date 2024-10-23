package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.http.TruckAccessParser;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.util.PMap;
import org.apache.commons.lang3.tuple.Pair;

import static com.graphhopper.customspeeds.CustomSpeedsUtils.validateCustomSpeedDirection;

public class ReplicaCustomSpeedsTruckAccessParser extends TruckAccessParser {
    private final ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed;
    private final boolean directionalCustomSpeedsProvided;

    public ReplicaCustomSpeedsTruckAccessParser(EncodedValueLookup lookup, PMap properties, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed, boolean directionalCustomSpeedsProvided) {
        super(lookup, properties);
        this.osmWayIdAndBwdToMaxSpeed = osmWayIdAndBwdToMaxSpeed;
        this.directionalCustomSpeedsProvided = directionalCustomSpeedsProvided;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
        super.handleWayTags(edgeId, edgeIntAccess, way);
        validateCustomSpeedDirection(osmWayIdAndBwdToMaxSpeed, directionalCustomSpeedsProvided, way.getId(), accessEnc, edgeId, edgeIntAccess);
    }
}

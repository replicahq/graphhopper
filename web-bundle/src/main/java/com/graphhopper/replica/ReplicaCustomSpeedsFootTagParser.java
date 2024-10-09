package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.FootAverageSpeedParser;
import com.graphhopper.util.PMap;
import org.apache.commons.lang3.tuple.Pair;

public class ReplicaCustomSpeedsFootTagParser extends FootAverageSpeedParser {
    private final ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed;

    // Replica-specific limit for custom walking speeds
    public static final int FOOT_MAX_SPEED = 10;
    
    // Copied directly from FootAverageSpeedParser
    static final int SLOW_SPEED = 2;
    static final int MEAN_SPEED = 5;

    public ReplicaCustomSpeedsFootTagParser(EncodedValueLookup lookup, PMap configuration, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed) {
        super(lookup, configuration);
        this.osmWayIdAndBwdToMaxSpeed = osmWayIdAndBwdToMaxSpeed;
    }

    // Copied directly from FootAverageSpeedParser, edited to call edited setSpeed() function
    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
        String highwayValue = way.getTag("highway");
        if (highwayValue == null) {
            if (way.hasTag("route", ferries)) {
                double ferrySpeed = ferrySpeedCalc.getSpeed(way);
                setSpeed(edgeId, edgeIntAccess, true, true, ferrySpeed, way);
            }
            if (!way.hasTag("railway", "platform") && !way.hasTag("man_made", "pier"))
                return;
        }

        String sacScale = way.getTag("sac_scale");
        if (sacScale != null) {
            setSpeed(edgeId, edgeIntAccess, true, true, "hiking".equals(sacScale) ? MEAN_SPEED : SLOW_SPEED, way);
        } else {
            setSpeed(edgeId, edgeIntAccess, true, true, way.hasTag("highway", "steps") ? MEAN_SPEED - 2 : MEAN_SPEED, way);
        }
    }

    // Overrides speed with custom speed, if one exists
    void setSpeed(int edgeId, EdgeIntAccess edgeIntAccess, boolean fwd, boolean bwd, double speed, ReaderWay way) {
         double speedToSet = CustomSpeedsUtils.getCustomMaxSpeed(way, osmWayIdAndBwdToMaxSpeed, bwd).orElse(speed);
         setSpeed(edgeId, edgeIntAccess, fwd, bwd, speedToSet);
    }

    // Copied directly from FootAverageSpeedParser
    void setSpeed(int edgeId, EdgeIntAccess edgeIntAccess, boolean fwd, boolean bwd, double speed) {
        if (speed > getMaxSpeed())
            speed = getMaxSpeed();
        if (fwd)
            avgSpeedEnc.setDecimal(false, edgeId, edgeIntAccess, speed);
        if (bwd && avgSpeedEnc.isStoreTwoDirections())
            avgSpeedEnc.setDecimal(true, edgeId, edgeIntAccess, speed);
    }
}

package com.graphhopper.stableid;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;

public class NoOpTagParser implements TagParser {
    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        return edgeFlags;
    }
}

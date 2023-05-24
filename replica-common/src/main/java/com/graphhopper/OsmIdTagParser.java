package com.graphhopper;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;

class OsmIdTagParser implements TagParser {

    private final IntEncodedValue osmIdEnc;

    public OsmIdTagParser(IntEncodedValue osmIdEnc) {
        this.osmIdEnc = osmIdEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        if (way.getId() > Integer.MAX_VALUE)
            throw new RuntimeException("Unexpectedly high way id.");
        osmIdEnc.setInt(false, edgeId, edgeIntAccess, (int) way.getId());
    }
}

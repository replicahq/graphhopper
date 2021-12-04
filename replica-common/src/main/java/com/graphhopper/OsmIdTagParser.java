package com.graphhopper;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.UnsignedIntEncodedValue;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;

import java.util.List;

class OsmIdTagParser implements TagParser {
    private EncodedValueLookup lookup;

    @Override
    public void createEncodedValues(EncodedValueLookup lookup, List<EncodedValue> encodedValues) {
        this.lookup = lookup;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef intsRef1) {
        UnsignedIntEncodedValue osmid = (UnsignedIntEncodedValue) lookup.getIntEncodedValue("osmid");
        if (way.getId() > Integer.MAX_VALUE)
            throw new RuntimeException("Unexpectedly high way id.");
        osmid.setInt(false, edgeFlags, (int) way.getId());
        return edgeFlags;
    }
}

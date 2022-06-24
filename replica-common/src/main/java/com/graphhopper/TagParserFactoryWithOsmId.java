package com.graphhopper;

import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.DefaultTagParserFactory;
import com.graphhopper.routing.util.parsers.TagParser;

public class TagParserFactoryWithOsmId extends DefaultTagParserFactory {

    @Override
    public TagParser create(EncodedValueLookup lookup, String name) {
        if (name.equals("osmid")) {
            return new OsmIdTagParser(lookup.getIntEncodedValue("osmid"));
        } else {
            return super.create(lookup, name);
        }
    }
}

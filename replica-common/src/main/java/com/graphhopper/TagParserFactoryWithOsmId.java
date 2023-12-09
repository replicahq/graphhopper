package com.graphhopper;

import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.parsers.DefaultTagParserFactory;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.stableid.NoOpTagParser;
import com.graphhopper.util.PMap;

public class TagParserFactoryWithOsmId extends DefaultTagParserFactory {

    @Override
    public TagParser create(EncodedValueLookup lookup, String name, PMap properties) {
        if (name.equals(RouterConstants.OSM_ID_ENCODED_VALUE)) {
            return new OsmIdTagParser(lookup.getIntEncodedValue(RouterConstants.OSM_ID_ENCODED_VALUE));
        } else if (name.startsWith("stable_id") || name.startsWith("reverse_stable_id")) {
            // We compute those values outside of GraphHopper's loop
            return new NoOpTagParser();
        } else {
            return super.create(lookup, name, properties);
        }
    }
}

/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.http;

import com.graphhopper.*;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.replica.TruckFlagEncoder;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.UnsignedIntEncodedValue;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.FlagEncoderFactory;
import com.graphhopper.routing.util.parsers.DefaultTagParserFactory;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.stableid.EncodedValueFactoryWithStableId;
import com.graphhopper.stableid.PathDetailsBuilderFactoryWithStableId;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.PMap;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class GraphHopperManaged implements Managed {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;

    public GraphHopperManaged(GraphHopperConfig configuration) {
        if (configuration.has("validation")) {
            graphHopper = new CustomGraphHopperValidator((configuration));
        } else if (configuration.has("gtfs.file")) {
            graphHopper = new CustomGraphHopperGtfs(configuration);
        } else {
            graphHopper = new CustomGraphHopperOSM(configuration);
        }

        graphHopper.setFlagEncoderFactory(new FlagEncoderFactory() {
            private FlagEncoderFactory delegate = new DefaultFlagEncoderFactory();

            @Override
            public FlagEncoder createFlagEncoder(String name, PMap configuration) {
                if (name.equals("truck")) {
                    return TruckFlagEncoder.createTruck(configuration, "truck");
                } else {
                    return delegate.createFlagEncoder(name, configuration);
                }
            }
        });
        graphHopper.setTagParserFactory(new DefaultTagParserFactory() {
            @Override
            public TagParser create(String name, PMap configuration) {
                if (name.equals("osmid")) {
                    return new TagParser() {
                        private EncodedValueLookup lookup;

                        @Override
                        public void createEncodedValues(EncodedValueLookup lookup, List<EncodedValue> encodedValues) {
                            this.lookup = lookup;
                        }

                        @Override
                        public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, boolean isFerry, IntsRef intsRef1) {
                            UnsignedIntEncodedValue osmid = (UnsignedIntEncodedValue) lookup.getIntEncodedValue("osmid");
                            if (way.getId() > Integer.MAX_VALUE)
                                throw new RuntimeException("Unexpectedly high way id.");
                            osmid.setInt(false, edgeFlags, (int) way.getId());
                            System.out.println("pups " + osmid.getInt(false, edgeFlags));
                            return edgeFlags;
                        }
                    };
                }
                return super.create(name, configuration);
            }
        });
        graphHopper.setEncodedValueFactory(new EncodedValueFactoryWithStableId());
        graphHopper.init(configuration);
        graphHopper.setPathDetailsBuilderFactory(new PathDetailsBuilderFactoryWithStableId());
        graphHopper.setAllowWrites(!Boolean.parseBoolean(System.getenv("GRAPHHOPPER_READ_ONLY")));
    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:{}, data_reader_file:{}, encoded values:{}, {} ints for edge flags, {}",
                graphHopper.getGraphHopperLocation(), graphHopper.getOSMFile(),
                graphHopper.getEncodingManager().toEncodedValuesAsString(),
                graphHopper.getEncodingManager().getIntsForFlags(),
                graphHopper.getGraphHopperStorage().toDetailsString());
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    @Override
    public void stop() {
        graphHopper.close();
    }
}

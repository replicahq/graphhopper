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

package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.http.TruckAccessParser;
import com.graphhopper.http.TruckAverageSpeedParser;
import com.graphhopper.http.TruckFlagEncoder;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.DefaultVehicleTagParserFactory;
import com.graphhopper.routing.util.VehicleTagParsers;
import com.graphhopper.routing.util.parsers.CarAccessParser;
import com.graphhopper.util.PMap;

import static com.graphhopper.http.TruckAverageSpeedParser.*;

public class ReplicaVehicleTagParserFactory extends DefaultVehicleTagParserFactory {
    private final ImmutableMap<String, ImmutableMap<Long, Double>> vehicleNameToCustomSpeeds;

    /**
     * @param vehicleNameToCustomSpeeds map of vehicle name to mapping from OSM way id to the custom speed to use for
     *                                  that way, in kph. vehicles without custom speeds may be omitted from the map.
     */
    public ReplicaVehicleTagParserFactory(ImmutableMap<String, ImmutableMap<Long, Double>> vehicleNameToCustomSpeeds) {
        this.vehicleNameToCustomSpeeds = vehicleNameToCustomSpeeds;
    }

    @Override
    public VehicleTagParsers createParsers(EncodedValueLookup lookup, String name, PMap configuration) {
        // TODO if we ever want to support custom speeds for vehicles other than car, we'll need to generalize
        // ReplicaCustomSpeedsCarTagParser
        if (vehicleNameToCustomSpeeds.containsKey(name)) {
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it
            PMap configWithName = new PMap(configuration).putObject("name", name);
            return new VehicleTagParsers(
                    new CarAccessParser(lookup, configuration).init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new ReplicaCustomSpeedsCarTagParser(lookup, configWithName, vehicleNameToCustomSpeeds.get(name)),
                    null
            );
        } else if (name.equals(TruckFlagEncoder.TRUCK_VEHICLE_NAME)) {
            configuration.putObject("block_fords", false);
            if (!configuration.has("name"))
                configuration = new PMap(configuration).putObject("name", "truck");
            if (!configuration.has("max_speed"))
                configuration = new PMap(configuration).putObject("max_speed", EE_TRUCK_MAX_SPEED);
            return new VehicleTagParsers(
                    new TruckAccessParser(lookup, configuration).
                            setHeight(3.7).setWidth(2.6, 0.34).setLength(12).
                            setWeight(13.0 + 13.0).setAxes(3).setIsHGV(true).
                            initProperties().
                            init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new TruckAverageSpeedParser(lookup, configuration).
                            setHeight(3.7).setWidth(2.6, 0.34).setLength(12).
                            setWeight(13.0 + 13.0).setAxes(3).setIsHGV(true).
                            initProperties(),
                    null);
        } else if (name.equals(TruckFlagEncoder.SMALL_TRUCK_VEHICLE_NAME)) {
            configuration.putObject("block_fords", false);
            if (!configuration.has("name"))
                configuration = new PMap(configuration).putObject("name", "small_truck");
            if (!configuration.has("max_speed"))
                configuration = new PMap(configuration).putObject("max_speed", EE_SMALL_TRUCK_MAX_SPEED);
            return new VehicleTagParsers(
                    new TruckAccessParser(lookup, configuration).
                            setHeight(2.7).setWidth(2, 0.34).setLength(5.5).
                            setWeight(SMALL_TRUCK_WEIGHT).
                            initProperties().
                            init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new TruckAverageSpeedParser(lookup, configuration).
                            setHeight(2.7).setWidth(2, 0.34).setLength(5.5).
                            setWeight(SMALL_TRUCK_WEIGHT)
                            .initProperties(),
                    null);
        }

        return super.createParsers(lookup, name, configuration);
    }
}

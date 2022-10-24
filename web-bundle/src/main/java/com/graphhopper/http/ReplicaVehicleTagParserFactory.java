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

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.CarTagParser;
import com.graphhopper.routing.util.DefaultVehicleTagParserFactory;
import com.graphhopper.routing.util.VehicleTagParser;
import com.graphhopper.util.PMap;

import java.util.Map;
import java.util.Objects;

public class ReplicaVehicleTagParserFactory extends DefaultVehicleTagParserFactory {
    private final Map<String, String> vehicleNameToCustomSpeedFileName;

    public ReplicaVehicleTagParserFactory(Map<String, String> vehicleNameToCustomSpeedFileName) {
        this.vehicleNameToCustomSpeedFileName = vehicleNameToCustomSpeedFileName;
    }

    @Override
    public VehicleTagParser createParser(EncodedValueLookup lookup, String name, PMap configuration) {
        configuration.putObject("block_fords", false);

        if (vehicleNameToCustomSpeedFileName.containsKey(name)) {
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it
            configuration.putObject("name", name);
            return new CarTagParser(lookup, configuration) {

                // Thurton Drive in Roseville, CA
                // TODO read from csv
                private Map<Long, Double> OSM_WAY_ID_TO_MAX_SPEED = Map.of(10485465L, 1000.0);

                @Override
                protected double applyMaxSpeed(ReaderWay way, double speed) {
                    Double knownMaxSpeed = OSM_WAY_ID_TO_MAX_SPEED.get(way.getId());
                    // n.b. this does the 90% of OSM max speed logic
                    return Objects.requireNonNullElseGet(knownMaxSpeed, () -> super.applyMaxSpeed(way, speed));
                }

                protected double applyBadSurfaceSpeed(ReaderWay way, double speed) {
                    // if we've been explicitly been given a speed to use for the way, we should not apply any
                    // additional logic for bad road surfaces
                    if (OSM_WAY_ID_TO_MAX_SPEED.containsKey(way.getId())) {
                        return speed;
                    }

                    return super.applyBadSurfaceSpeed(way, speed);
                }
            };
        }

        if (name.equals("truck")) {
            return CarAndTruckTagParser.createTruck(lookup, configuration);
        } else if (name.startsWith("car")) {
            return CarAndTruckTagParser.createCar(lookup, configuration);
        }

        return super.createParser(lookup, name, configuration);
    }
}

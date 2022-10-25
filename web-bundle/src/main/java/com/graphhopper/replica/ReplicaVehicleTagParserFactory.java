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
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.http.TruckFlagEncoder;
import com.graphhopper.http.TruckTagParser;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.DefaultVehicleTagParserFactory;
import com.graphhopper.routing.util.VehicleTagParser;
import com.graphhopper.util.PMap;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class ReplicaVehicleTagParserFactory extends DefaultVehicleTagParserFactory {
    private final Map<String, File> vehicleNameToCustomSpeedFile;

    public ReplicaVehicleTagParserFactory(Map<String, File> vehicleNameToCustomSpeedFile) {
        this.vehicleNameToCustomSpeedFile = vehicleNameToCustomSpeedFile;
    }

    @Override
    public VehicleTagParser createParser(EncodedValueLookup lookup, String name, PMap configuration) {
        if (vehicleNameToCustomSpeedFile.containsKey(name)) {
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it
            configuration.putObject("name", name);
            File customSpeedFile = vehicleNameToCustomSpeedFile.get(name);

            try {
                // we read the custom speeds mapping into memory so it can be efficiently applied during OSM import. all
                // custom speed mappings will be simultaneously held in memory, but each custom speed file should be
                // <= 250 MB (nationwide speed mapping file is 235MB)
                ImmutableMap<Long, Double> osmWayIdToMaxSpeed =
                        CustomSpeedsUtils.parseOsmWayIdToMaxSpeed(customSpeedFile);
                return new ReplicaCustomSpeedsCarTagParser(lookup, configuration, osmWayIdToMaxSpeed);
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse custom speed file at path "
                        + customSpeedFile.getAbsolutePath() + ". Please ensure file exists and is in the correct " +
                        "format!", e);
            }
        } else if (name.equals(TruckFlagEncoder.TRUCK_VEHICLE_NAME)) {
            configuration.putObject("block_fords", false);
            return TruckTagParser.createTruck(lookup, configuration);
        }

        return super.createParser(lookup, name, configuration);
    }
}

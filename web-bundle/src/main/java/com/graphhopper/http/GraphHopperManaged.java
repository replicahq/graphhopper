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
import com.graphhopper.customspeeds.CustomSpeeds;
import com.graphhopper.stableid.EncodedValueFactoryWithStableId;
import com.graphhopper.stableid.PathDetailsBuilderFactoryWithStableId;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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

        Map<String, String> vehicleNameToCustomSpeedFileName = CustomSpeeds.getVehicleNameToCustomSpeedFileName(configuration.getProfiles());

        graphHopper.setEncodedValueFactory(new EncodedValueFactoryWithStableId());
        graphHopper.setTagParserFactory(new TagParserFactoryWithOsmId());
        graphHopper.setVehicleTagParserFactory(new ReplicaVehicleTagParserFactory(vehicleNameToCustomSpeedFileName));
        graphHopper.init(configuration);
        graphHopper.setEncodedValuesString("osmid,stable_id_byte_0,stable_id_byte_1,stable_id_byte_2,stable_id_byte_3,stable_id_byte_4,stable_id_byte_5,stable_id_byte_6,stable_id_byte_7,reverse_stable_id_byte_0,reverse_stable_id_byte_1,reverse_stable_id_byte_2,reverse_stable_id_byte_3,reverse_stable_id_byte_4,reverse_stable_id_byte_5,reverse_stable_id_byte_6,reverse_stable_id_byte_7");
        graphHopper.setPathDetailsBuilderFactory(new PathDetailsBuilderFactoryWithStableId());
        graphHopper.setAllowWrites(!Boolean.parseBoolean(System.getenv("GRAPHHOPPER_READ_ONLY")));

        graphHopper.setFlagEncoderFactory(new ReplicaFlagEncoderFactory(vehicleNameToCustomSpeedFileName.keySet()));

    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:{}, data_reader_file:{}, encoded values:{}, {} ints for edge flags, {}",
                graphHopper.getGraphHopperLocation(), graphHopper.getOSMFile(),
                graphHopper.getEncodingManager().toEncodedValuesAsString(),
                graphHopper.getEncodingManager().getIntsForFlags(),
                graphHopper.getBaseGraph().toDetailsString());
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    @Override
    public void stop() {
        graphHopper.close();
    }
}

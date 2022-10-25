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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.graphhopper.*;
import com.graphhopper.config.Profile;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.replica.ReplicaFlagEncoderFactory;
import com.graphhopper.replica.ReplicaVehicleTagParserFactory;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.stableid.EncodedValueFactoryWithStableId;
import com.graphhopper.stableid.PathDetailsBuilderFactoryWithStableId;
import com.graphhopper.util.CustomModel;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

        String customModelFolder = configuration.getString("custom_model_folder", "");
        List<Profile> newProfiles = resolveCustomModelFiles(customModelFolder, configuration.getProfiles());
        configuration.setProfiles(newProfiles);

        Map<String, File> vehicleNameToCustomSpeedFile = CustomSpeedsUtils.getVehicleNameToCustomSpeedFile(
                configuration.getProfiles());

        graphHopper.setEncodedValueFactory(new EncodedValueFactoryWithStableId());
        graphHopper.setTagParserFactory(new TagParserFactoryWithOsmId());
        graphHopper.setVehicleTagParserFactory(new ReplicaVehicleTagParserFactory(vehicleNameToCustomSpeedFile));
        graphHopper.setFlagEncoderFactory(new ReplicaFlagEncoderFactory(vehicleNameToCustomSpeedFile.keySet()));
        graphHopper.init(configuration);
        graphHopper.setEncodedValuesString("osmid,stable_id_byte_0,stable_id_byte_1,stable_id_byte_2,stable_id_byte_3,stable_id_byte_4,stable_id_byte_5,stable_id_byte_6,stable_id_byte_7,reverse_stable_id_byte_0,reverse_stable_id_byte_1,reverse_stable_id_byte_2,reverse_stable_id_byte_3,reverse_stable_id_byte_4,reverse_stable_id_byte_5,reverse_stable_id_byte_6,reverse_stable_id_byte_7");
        graphHopper.setPathDetailsBuilderFactory(new PathDetailsBuilderFactoryWithStableId());
        graphHopper.setAllowWrites(!Boolean.parseBoolean(System.getenv("GRAPHHOPPER_READ_ONLY")));
    }

    // Copied directly from GraphHopperManaged.java on commit b62dfbac89a4a79eb5bab305707c528e7bef35f5
    public static List<Profile> resolveCustomModelFiles(String customModelFolder, List<Profile> profiles) {
        ObjectMapper yamlOM = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
        ObjectMapper jsonOM = Jackson.newObjectMapper();
        List<Profile> newProfiles = new ArrayList<>();
        for (Profile profile : profiles) {
            if (!CustomWeighting.NAME.equals(profile.getWeighting())) {
                newProfiles.add(profile);
                continue;
            }
            Object cm = profile.getHints().getObject("custom_model", null);
            if (cm != null) {
                try {
                    // custom_model can be an object tree (read from config) or an object (e.g. from tests)
                    CustomModel customModel = jsonOM.readValue(jsonOM.writeValueAsBytes(cm), CustomModel.class);
                    newProfiles.add(new CustomProfile(profile).setCustomModel(customModel));
                    continue;
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot load custom_model from " + cm + " for profile " + profile.getName(), ex);
                }
            }
            String customModelFileName = profile.getHints().getString("custom_model_file", "");
            if (customModelFileName.isEmpty())
                throw new IllegalArgumentException("Missing 'custom_model' or 'custom_model_file' field in profile '"
                        + profile.getName() + "'. To use default specify custom_model_file: empty");
            if ("empty".equals(customModelFileName))
                newProfiles.add(new CustomProfile(profile).setCustomModel(new CustomModel()));
            else {
                if (customModelFileName.contains(File.separator))
                    throw new IllegalArgumentException("Use custom_model_folder for the custom_model_file parent");
                // Somehow dropwizard makes it very hard to find out the folder of config.yml -> use an extra parameter for the folder
                File file = Paths.get(customModelFolder).resolve(customModelFileName).toFile();
                try {
                    CustomModel customModel = (customModelFileName.endsWith(".json") ? jsonOM : yamlOM).readValue(file, CustomModel.class);
                    newProfiles.add(new CustomProfile(profile).setCustomModel(customModel));
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot load custom_model from location " + customModelFileName + " for profile " + profile.getName(), ex);
                }
            }
        }
        return newProfiles;
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

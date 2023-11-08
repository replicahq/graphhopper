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
import com.google.common.collect.ImmutableMap;
import com.graphhopper.*;
import com.graphhopper.config.Profile;
import com.graphhopper.customspeeds.CustomSpeedsUtils;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.replica.ReplicaVehicleEncodedValuesFactory;
import com.graphhopper.replica.ReplicaVehicleTagParserFactory;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.stableid.EncodedValueFactoryWithStableId;
import com.graphhopper.stableid.PathDetailsBuilderFactoryWithStableId;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.Helper;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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

        String customModelFolder = configuration.getString("custom_model_folder", "");
        List<Profile> newProfiles = resolveCustomModelFiles(customModelFolder, configuration.getProfiles());
        configuration.setProfiles(newProfiles);

        // we read all custom speeds mappings into memory so they can be efficiently applied during OSM import. each
        // custom speed file should be <= 250 MB (nationwide speed mapping file is 235MB)
        ImmutableMap<String, ImmutableMap<Long, Double>> vehicleNameToCustomSpeeds =
                CustomSpeedsUtils.getVehicleNameToCustomSpeeds(configuration.getProfiles());

        graphHopper.setEncodedValueFactory(new EncodedValueFactoryWithStableId());
        graphHopper.setTagParserFactory(new TagParserFactoryWithOsmId());
        graphHopper.setVehicleTagParserFactory(new ReplicaVehicleTagParserFactory(vehicleNameToCustomSpeeds));
        graphHopper.setVehicleEncodedValuesFactory(new ReplicaVehicleEncodedValuesFactory(vehicleNameToCustomSpeeds.keySet()));
        graphHopper.init(configuration);
        graphHopper.setEncodedValuesString("osmid,stable_id_byte_0,stable_id_byte_1,stable_id_byte_2,stable_id_byte_3,stable_id_byte_4,stable_id_byte_5,stable_id_byte_6,stable_id_byte_7,reverse_stable_id_byte_0,reverse_stable_id_byte_1,reverse_stable_id_byte_2,reverse_stable_id_byte_3,reverse_stable_id_byte_4,reverse_stable_id_byte_5,reverse_stable_id_byte_6,reverse_stable_id_byte_7");
        graphHopper.setPathDetailsBuilderFactory(new PathDetailsBuilderFactoryWithStableId());
        graphHopper.setAllowWrites(!Boolean.parseBoolean(System.getenv("GRAPHHOPPER_READ_ONLY")));
    }

    // MUST UPDATE EVERY TIME A "GRAPHHOPPER CORE UPDATE" OCCURS
    // Copied directly from GraphHopperManaged.java on commit 99682c42c3df04226349421c82c44fa0474eedb4
    public static List<Profile> resolveCustomModelFiles(String customModelFolder, List<Profile> profiles) {
        ObjectMapper jsonOM = Jackson.newObjectMapper();
        List<Profile> newProfiles = new ArrayList<>();
        for (Profile profile : profiles) {
            if (!CustomWeighting.NAME.equals(profile.getWeighting())) {
                newProfiles.add(profile);
                continue;
            }
            Object cm = profile.getHints().getObject(CustomModel.KEY, null);
            CustomModel customModel;
            if (cm != null) {
                if (!profile.getHints().getObject("custom_model_files", Collections.emptyList()).isEmpty())
                    throw new IllegalArgumentException("Do not use custom_model_files and custom_model together");
                try {
                    // custom_model can be an object tree (read from config) or an object (e.g. from tests)
                    customModel = jsonOM.readValue(jsonOM.writeValueAsBytes(cm), CustomModel.class);
                    newProfiles.add(new CustomProfile(profile).setCustomModel(customModel));
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot load custom_model from " + cm + " for profile " + profile.getName()
                            + ". If you are trying to load from a file, use 'custom_model_files' instead.", ex);
                }
            } else {
                if (!profile.getHints().getString("custom_model_file", "").isEmpty())
                    throw new IllegalArgumentException("Since 8.0 you must use a custom_model_files array instead of custom_model_file string");
                List<String> customModelFileNames = profile.getHints().getObject("custom_model_files", null);
                if (customModelFileNames == null)
                    throw new IllegalArgumentException("Missing 'custom_model' or 'custom_model_files' field in profile '"
                            + profile.getName() + "'. To use default specify custom_model_files: []");
                if (customModelFileNames.isEmpty()) {
                    newProfiles.add(new CustomProfile(profile).setCustomModel(customModel = new CustomModel()));
                } else {
                    customModel = new CustomModel();
                    for (String file : customModelFileNames) {
                        if (file.contains(File.separator))
                            throw new IllegalArgumentException("Use custom_models.directory for the custom_model_files parent");
                        if (!file.endsWith(".json"))
                            throw new IllegalArgumentException("Yaml is no longer supported, see #2672. Use JSON with optional comments //");
                        try {
                            // Somehow dropwizard makes it very hard to find out the folder of config.yml -> use an extra parameter for the folder
                            String string = Helper.readJSONFileWithoutComments(Paths.get(customModelFolder).
                                    resolve(file).toFile().getAbsolutePath());
                            customModel = CustomModel.merge(customModel, jsonOM.readValue(string, CustomModel.class));
                        } catch (Exception ex) {
                            throw new RuntimeException("Cannot load custom_model from location " + file + " for profile " + profile.getName(), ex);
                        }
                    }

                    newProfiles.add(new CustomProfile(profile).setCustomModel(customModel));
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

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
import com.graphhopper.GraphHopper;
import com.graphhopper.json.geo.JsonFeatureCollection;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.ee.vehicles.TruckFlagEncoder;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.FlagEncoderFactory;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.routing.util.spatialrules.SpatialRuleLookupHelper;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.swl.*;
import com.graphhopper.util.CmdArgs;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.BBox;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

import static com.graphhopper.util.Helper.UTF_CS;

public class GraphHopperManaged implements Managed {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;
    private CustomCarFlagEncoder customCarFlagEncoder;

    public GraphHopperManaged(CmdArgs configuration, ObjectMapper objectMapper) {
        String linkSpeedFile = configuration.get("r5.link_speed_file", null);
        final SpeedCalculator speedCalculator;
        if (linkSpeedFile != null) {
            speedCalculator = new FileSpeedCalculator(linkSpeedFile);
        } else {
            speedCalculator = new DefaultSpeedCalculator();
        }
        String splitAreaLocation = configuration.get(Parameters.Landmark.PREPARE + "split_area_location", "");
        JsonFeatureCollection landmarkSplittingFeatureCollection;
        try (Reader reader = splitAreaLocation.isEmpty() ? new InputStreamReader(LandmarkStorage.class.getResource("map.geo.json").openStream(), UTF_CS) : new InputStreamReader(new FileInputStream(splitAreaLocation), UTF_CS)) {
            landmarkSplittingFeatureCollection = objectMapper.readValue(reader, JsonFeatureCollection.class);
        } catch (IOException e1) {
            logger.error("Problem while reading border map GeoJSON. Skipping this.", e1);
            landmarkSplittingFeatureCollection = null;
        }
        graphHopper = new GraphHopperOSM(landmarkSplittingFeatureCollection) {
            @Override
            public Weighting createWeighting(HintsMap hintsMap, FlagEncoder encoder, Graph graph) {
                if (hintsMap.getWeighting().equals("td")) {
                    return new FastestCarTDWeighting(encoder, speedCalculator, hintsMap);
                } else {
                    return super.createWeighting(hintsMap, encoder, graph);
                }
            }
        }.forServer();
        String spatialRuleLocation = configuration.get("spatial_rules.location", "");
        if (!spatialRuleLocation.isEmpty()) {
            final BBox maxBounds = BBox.parseBBoxString(configuration.get("spatial_rules.max_bbox", "-180, 180, -90, 90"));
            try (final InputStreamReader reader = new InputStreamReader(new FileInputStream(spatialRuleLocation), UTF_CS)) {
                JsonFeatureCollection jsonFeatureCollection = objectMapper.readValue(reader, JsonFeatureCollection.class);
                SpatialRuleLookupHelper.buildAndInjectSpatialRuleIntoGH(graphHopper, maxBounds, jsonFeatureCollection);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        graphHopper.setFlagEncoderFactory(new FlagEncoderFactory() {
            private FlagEncoderFactory delegate = new DefaultFlagEncoderFactory();
            @Override
            public FlagEncoder createFlagEncoder(String name, PMap configuration) {
                if (name.equals("car")) {
                    customCarFlagEncoder = new CustomCarFlagEncoder(configuration);
                    return customCarFlagEncoder;
                } else if (name.equals("truck")) {
                    return TruckFlagEncoder.createTruck(configuration, "truck");
                }
                return delegate.createFlagEncoder(name, configuration);
            }
        });
        graphHopper.init(configuration);
        graphHopper.setPathDetailsBuilderFactory(new PathDetailsBuilderFactoryWithEdgeKey(customCarFlagEncoder));
    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:" + graphHopper.getGraphHopperLocation()
                + ", data_reader_file:" + graphHopper.getDataReaderFile()
                + ", encoded values:" + graphHopper.getEncodingManager().toEncodedValuesAsString()
                + ", " + graphHopper.getGraphHopperStorage().toDetailsString());
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    @Override
    public void stop() {
        graphHopper.close();
    }


}

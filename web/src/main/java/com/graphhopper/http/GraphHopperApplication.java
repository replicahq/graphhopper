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

import com.graphhopper.http.cli.*;
import com.graphhopper.http.resources.RootResource;
import io.dropwizard.Application;
import io.dropwizard.bundles.assets.ConfiguredAssetsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import javax.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public final class GraphHopperApplication extends Application<GraphHopperServerConfiguration> {

    public static void main(String[] args) throws Exception {
        new GraphHopperApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<GraphHopperServerConfiguration> bootstrap) {
        bootstrap.addBundle(new GraphHopperBundle());
        bootstrap.addBundle(new RealtimeBundle());
        bootstrap.addCommand(new ImportCommand());
        bootstrap.addCommand(new ImportSandboxCommand());
        bootstrap.addCommand(new GtfsLinkMapperCommand());
        bootstrap.addCommand(new ExportNationwideCommand());
        bootstrap.addCommand(new ValidateGTFSCommand());

        Map<String, String> resourceToURIMappings = new HashMap<>();
        resourceToURIMappings.put("/src/main/resources/assets/", "/maps/");
        resourceToURIMappings.put("/META-INF/resources/webjars", "/webjars"); // https://www.webjars.org/documentation#dropwizard
        bootstrap.addBundle(new ConfiguredAssetsBundle(resourceToURIMappings, "index.html"));
    }

    @Override
    public void run(GraphHopperServerConfiguration configuration, Environment environment) throws Exception {
        environment.jersey().register(new RootResource());
        environment.servlets().addFilter("cors", CORSFilter.class).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "*");
    }
}

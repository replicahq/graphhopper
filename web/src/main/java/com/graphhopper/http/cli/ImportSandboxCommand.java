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

package com.graphhopper.http.cli;

import com.graphhopper.CustomGraphHopperOSM;
import com.graphhopper.http.GraphHopperManaged;
import com.graphhopper.http.GraphHopperServerConfiguration;
import com.graphhopper.replica.StableEdgeIdManager;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;

public class ImportSandboxCommand extends ConfiguredCommand<GraphHopperServerConfiguration> {

    public ImportSandboxCommand() {
        super("import-sandbox", "creates the graphhopper files used for sandbox server starts");
    }

    @Override
    protected void run(Bootstrap<GraphHopperServerConfiguration> bootstrap, Namespace namespace, GraphHopperServerConfiguration configuration) {
        final GraphHopperManaged graphHopper = new GraphHopperManaged(configuration.getGraphHopperConfiguration());
        CustomGraphHopperOSM gh = (CustomGraphHopperOSM) graphHopper.getGraphHopper();
        gh.importOrLoad();
        StableEdgeIdManager stableEdgeIdManager = new StableEdgeIdManager(gh, gh.getOsmHelper());
        stableEdgeIdManager.setStableEdgeIds();
        gh.close();
    }
}

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

package com.graphhopper.swl;

import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.details.*;

import java.util.ArrayList;
import java.util.List;

import static com.graphhopper.util.Parameters.DETAILS.*;

public class PathDetailsBuilderFactoryWithEdgeKey extends PathDetailsBuilderFactory {

    private final CustomCarFlagEncoder customCarFlagEncoder;

    public PathDetailsBuilderFactoryWithEdgeKey(CustomCarFlagEncoder customCarFlagEncoder) {
        this.customCarFlagEncoder = customCarFlagEncoder;
    }

    @Override
    public List<PathDetailsBuilder> createPathDetailsBuilders(List<String> requestedPathDetails, FlagEncoder encoder, Weighting weighting) {
        // request-scoped
        List<PathDetailsBuilder> builders = new ArrayList<>();
        if (requestedPathDetails.contains(AVERAGE_SPEED))
            builders.add(new DecimalDetails(AVERAGE_SPEED, encoder.getAverageSpeedEnc()));

        if (requestedPathDetails.contains(STREET_NAME))
            builders.add(new StreetNameDetails());

        if (requestedPathDetails.contains(EDGE_ID))
            builders.add(new EdgeIdDetails());

        if (requestedPathDetails.contains(TIME))
            builders.add(new TimeDetails(weighting));

        if (requestedPathDetails.contains("edge_key")) {
            builders.add(new EdgeKeyDetails());
        }

        if (requestedPathDetails.contains("r5_edge_id")) {
            builders.add(new R5EdgeIdPathDetailsBuilder(customCarFlagEncoder));
        }

        if (requestedPathDetails.size() != builders.size()) {
            throw new IllegalArgumentException("You requested the details " + requestedPathDetails + " but we could only find " + builders);
        }

        return builders;
    }
}
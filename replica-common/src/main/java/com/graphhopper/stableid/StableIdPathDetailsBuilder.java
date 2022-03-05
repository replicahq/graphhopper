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

package com.graphhopper.stableid;

import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.details.AbstractPathDetailsBuilder;

public class StableIdPathDetailsBuilder extends AbstractPathDetailsBuilder {
    private final StableIdEncodedValues originalDirectionFlagEncoder;
    private int prevEdgeId = -1;
    private String edgeId;

    public StableIdPathDetailsBuilder(EncodedValueLookup originalDirectionFlagEncoder) {
        super("stable_edge_ids");
        this.originalDirectionFlagEncoder = StableIdEncodedValues.fromEncodingManager((EncodingManager) originalDirectionFlagEncoder);
        edgeId = "";
    }

    @Override
    public boolean isEdgeDifferentToLastEdge(EdgeIteratorState edge) {
        if (edge.getEdge() != this.prevEdgeId) {
            edgeId = getStableId(edge);
            this.prevEdgeId = edge.getEdge();
            return true;
        } else {
            return false;
        }
    }

    private String getStableId(EdgeIteratorState edge) {
        boolean reverse = edge.get(EdgeIteratorState.REVERSE_STATE);
        return originalDirectionFlagEncoder.getStableId(reverse, edge);
    }

    @Override
    public Object getCurrentValue() {
        return this.edgeId;
    }
}

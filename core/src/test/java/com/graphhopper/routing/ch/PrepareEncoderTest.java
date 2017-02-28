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
package com.graphhopper.routing.ch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author Peter Karich
 */
public class PrepareEncoderTest {

    @Test
    public void testOverwrite() {
        long forward = PrepareEncoder.getScFwdDir();
        long backward = PrepareEncoder.getScFwdDir() ^ PrepareEncoder.getScDirMask();
        long both = PrepareEncoder.getScDirMask();
        assertEquals(1, PrepareEncoder.getMergeStatus(forward, forward));
        assertEquals(1, PrepareEncoder.getMergeStatus(backward, backward));
        assertEquals(2, PrepareEncoder.getMergeStatus(forward, both));
        assertEquals(2, PrepareEncoder.getMergeStatus(backward, both));

        assertEquals(1, PrepareEncoder.getMergeStatus(both, both));
        assertEquals(0, PrepareEncoder.getMergeStatus(both, forward));
        assertEquals(0, PrepareEncoder.getMergeStatus(both, backward));
        assertEquals(0, PrepareEncoder.getMergeStatus(forward, backward));
        assertEquals(0, PrepareEncoder.getMergeStatus(backward, forward));
    }
}

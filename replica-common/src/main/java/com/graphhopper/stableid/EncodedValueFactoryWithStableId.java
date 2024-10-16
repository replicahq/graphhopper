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

import com.graphhopper.routing.ev.DefaultEncodedValueFactory;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.IntEncodedValueImpl;
import com.graphhopper.util.PMap;

public class EncodedValueFactoryWithStableId extends DefaultEncodedValueFactory {

    @Override
    public EncodedValue create(String name, PMap properties) {
        if (name.startsWith("stable_id_byte_0")) {
            return new IntEncodedValueImpl("stable_id_byte_0", 8, false);
        } else if (name.startsWith("stable_id_byte_1")) {
            return new IntEncodedValueImpl("stable_id_byte_1", 8, false);
        } else if (name.startsWith("stable_id_byte_2")) {
            return new IntEncodedValueImpl("stable_id_byte_2", 8, false);
        } else if (name.startsWith("stable_id_byte_3")) {
            return new IntEncodedValueImpl("stable_id_byte_3", 8, false);
        } else if (name.startsWith("stable_id_byte_4")) {
            return new IntEncodedValueImpl("stable_id_byte_4", 8, false);
        } else if (name.startsWith("stable_id_byte_5")) {
            return new IntEncodedValueImpl("stable_id_byte_5", 8, false);
        } else if (name.startsWith("stable_id_byte_6")) {
            return new IntEncodedValueImpl("stable_id_byte_6", 8, false);
        } else if (name.startsWith("stable_id_byte_7")) {
            return new IntEncodedValueImpl("stable_id_byte_7", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_0")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_0", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_1")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_1", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_2")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_2", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_3")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_3", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_4")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_4", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_5")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_5", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_6")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_6", 8, false);
        } else if (name.startsWith("reverse_stable_id_byte_7")) {
            return new IntEncodedValueImpl("reverse_stable_id_byte_7", 8, false);
        } else if (name.startsWith("osmid")) {
            return new IntEncodedValueImpl("osmid", 31, false);
        } else {
            return super.create(name, properties);
        }
    }
}

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

package com.graphhopper.replica;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.RouterConstants;
import com.graphhopper.customspeeds.CustomSpeedsVehicle;
import com.graphhopper.http.TruckAccessParser;
import com.graphhopper.http.TruckAverageSpeedParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.DefaultVehicleTagParserFactory;
import com.graphhopper.routing.util.VehicleTagParsers;
import com.graphhopper.routing.util.parsers.*;
import com.graphhopper.util.PMap;
import org.apache.commons.lang3.tuple.Pair;

import static com.graphhopper.http.TruckAverageSpeedParser.*;

public class ReplicaVehicleTagParserFactory extends DefaultVehicleTagParserFactory {
    private final ImmutableMap<String, CustomSpeedsVehicle> customSpeedsVehiclesByName;

    /**
     * @param customSpeedsVehiclesByName map of custom vehicle name to CustomSpeedsVehicle object containing the custom
     *                                   speeds mapping and the base vehicle type. the speeds mapping must be expressed
     *                                   in kph. vehicles without custom speeds should be omitted from the map.
     */
    public ReplicaVehicleTagParserFactory(ImmutableMap<String, CustomSpeedsVehicle> customSpeedsVehiclesByName) {
        this.customSpeedsVehiclesByName = customSpeedsVehiclesByName;
    }

    @Override
    public VehicleTagParsers createParsers(EncodedValueLookup lookup, String vehicleName, PMap configuration) {
        // assume no custom speeds by default
        ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToCustomSpeed = ImmutableMap.of();
        CustomSpeedsVehicle.VehicleType baseCustomSpeedsVehicleType = null;

        if (customSpeedsVehiclesByName.containsKey(vehicleName)) {
            CustomSpeedsVehicle customSpeedsVehicle = customSpeedsVehiclesByName.get(vehicleName);
            osmWayIdAndBwdToCustomSpeed = customSpeedsVehicle.osmWayIdAndBwdToCustomSpeed;
            baseCustomSpeedsVehicleType = customSpeedsVehicle.baseVehicleType;
            // vehicles with custom speeds use nonstandard vehicle names which must be added to the config for the GH
            // internals to tolerate it
            configuration.putObject("name", vehicleName);
        }

        if (baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.CAR) {
            return new VehicleTagParsers(
                    new CarAccessParser(lookup, configuration).init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new ReplicaCustomSpeedsCarTagParser(lookup, configuration, osmWayIdAndBwdToCustomSpeed),
                    null
            );
        } else if (baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.BIKE) {
            return new VehicleTagParsers(
                    new BikeAccessParser(lookup, configuration).init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new ReplicaCustomSpeedsBikeTagParser(lookup, configuration, osmWayIdAndBwdToCustomSpeed),
                    new BikePriorityParser(lookup, configuration)
            );
        } else if (baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.FOOT) {
            return new VehicleTagParsers(
                    new FootAccessParser(lookup, configuration).init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new ReplicaCustomSpeedsFootTagParser(lookup, configuration, osmWayIdAndBwdToCustomSpeed),
                    new FootPriorityParser(lookup, configuration)
            );
        } else if (vehicleName.equals(RouterConstants.CAR_VEHICLE_NAME)
                || vehicleName.equals(RouterConstants.BIKE_VEHICLE_NAME)
                || vehicleName.equals(RouterConstants.FOOT_VEHICLE_NAME)) {
            // do nothing and carry through to superclass implementation. we could use pass an empty custom speeds mapping
            // to ReplicaCustomSpeedsCarTagParser, but it's safer to use the default GraphHopper behavior directly
        } else if (vehicleName.equals(RouterConstants.TRUCK_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.TRUCK) {
            configuration.putObject("block_fords", false);
            if (!configuration.has("name"))
                configuration = new PMap(configuration).putObject("name", "truck");
            if (!configuration.has("max_speed"))
                configuration = new PMap(configuration).putObject("max_speed", EE_TRUCK_MAX_SPEED);
            return new VehicleTagParsers(
                    new TruckAccessParser(lookup, configuration).
                            setHeight(3.7).setWidth(2.6, 0.34).setLength(12).
                            setWeight(13.0 + 13.0).setAxes(3).setIsHGV(true).
                            initProperties().
                            init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new TruckAverageSpeedParser(lookup, configuration, osmWayIdAndBwdToCustomSpeed).
                            setHeight(3.7).setWidth(2.6, 0.34).setLength(12).
                            setWeight(13.0 + 13.0).setAxes(3).setIsHGV(true).
                            initProperties(),
                    null);
        } else if (vehicleName.equals(RouterConstants.SMALL_TRUCK_VEHICLE_NAME) || baseCustomSpeedsVehicleType == CustomSpeedsVehicle.VehicleType.SMALL_TRUCK) {
            configuration.putObject("block_fords", false);
            if (!configuration.has("name"))
                configuration = new PMap(configuration).putObject("name", "small_truck");
            if (!configuration.has("max_speed"))
                configuration = new PMap(configuration).putObject("max_speed", EE_SMALL_TRUCK_MAX_SPEED);
            return new VehicleTagParsers(
                    new TruckAccessParser(lookup, configuration).
                            setHeight(2.7).setWidth(2, 0.34).setLength(5.5).
                            setWeight(SMALL_TRUCK_WEIGHT).
                            initProperties().
                            init(configuration.getObject("date_range_parser", new DateRangeParser())),
                    new TruckAverageSpeedParser(lookup, configuration, osmWayIdAndBwdToCustomSpeed).
                            setHeight(2.7).setWidth(2, 0.34).setLength(5.5).
                            setWeight(SMALL_TRUCK_WEIGHT)
                            .initProperties(),
                    null);
        }

        return super.createParsers(lookup, vehicleName, configuration);
    }
}

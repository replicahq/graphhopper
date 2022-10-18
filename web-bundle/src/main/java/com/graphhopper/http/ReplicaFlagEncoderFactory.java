package com.graphhopper.http;

import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultFlagEncoderFactory;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.VehicleEncodedValues;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.graphhopper.routing.util.EncodingManager.getKey;

public class ReplicaFlagEncoderFactory extends DefaultFlagEncoderFactory {

    Logger logger = LoggerFactory.getLogger(ReplicaFlagEncoderFactory.class);

    @Override
    public FlagEncoder createFlagEncoder(final String name, PMap configuration) {
        if (name.equals("car")) {
            return createEEEncoder(name, configuration, 7, 2, CarAndTruckTagParser.EE_CAR_MAX_SPEED, false);
        } else if (name.equals("small_truck")) {
            return createEEEncoder(name, configuration, 7, 2, CarAndTruckTagParser.EE_SMALL_TRUCK_MAX_SPEED, false);
        } else if (name.equals("truck")) {
            return createEEEncoder(name, configuration, 6, 2, CarAndTruckTagParser.EE_TRUCK_MAX_SPEED, true);
        } else if (name.equals("van")) {
            return createEEEncoder(name, configuration, 7, 2, CarAndTruckTagParser.EE_CAR_MAX_SPEED, false);
        } else if (name.startsWith("car_custom_speeds")) {
            return createEEEncoder(name, configuration, 7, 2, CarAndTruckTagParser.EE_CAR_MAX_SPEED, false);
        }
        return super.createFlagEncoder(name, configuration);
    }

    private static FlagEncoder createEEEncoder(String name, PMap properties, int speedBits, double speedFactor, double maxSpeed, boolean isHGV) {
        int maxTurnCosts = properties.getInt("max_turn_costs", properties.getBool("turn_costs", true) ? 1 : 0);
        BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue(getKey(name, "access"), true);
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl(getKey(name, "average_speed"), speedBits, speedFactor, false);
        DecimalEncodedValue turnCostEnc = maxTurnCosts > 0 ? TurnCost.create(name, maxTurnCosts) : null;
        return new VehicleEncodedValues(name, accessEnc, speedEnc, null, null, turnCostEnc, speedEnc.getNextStorableValue(maxSpeed), true, isHGV);
    }

}
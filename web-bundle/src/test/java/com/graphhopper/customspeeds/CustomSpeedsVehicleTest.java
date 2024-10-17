package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CustomSpeedsVehicleTest {

    @Test
    public void testInvalidSpeeds() {
        ImmutableMap<Pair<Long, Boolean>, Double> invalidCarCustomSpeeds = ImmutableMap.of(Pair.of(1L, Boolean.TRUE), 200.0, Pair.of(2L, Boolean.FALSE), 2.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("car_invalid", invalidCarCustomSpeeds));

        ImmutableMap<Pair<Long, Boolean>, Double> invalidSmallTruckCustomSpeeds = ImmutableMap.of(Pair.of(1L, Boolean.TRUE), 150.0, Pair.of(2L, Boolean.FALSE), -1.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("small_truck_invalid", invalidSmallTruckCustomSpeeds));

        ImmutableMap<Pair<Long, Boolean>, Double> invalidTruckCustomSpeeds = ImmutableMap.of(Pair.of(1L, Boolean.TRUE), 100.0, Pair.of(2L, Boolean.FALSE), 0.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("truck_invalid", invalidTruckCustomSpeeds));

        ImmutableMap<Pair<Long, Boolean>, Double> invalidBikeCustomSpeeds = ImmutableMap.of(Pair.of(1L, Boolean.TRUE), 35.0, Pair.of(2L, Boolean.FALSE), 0.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("bike_invalid", invalidBikeCustomSpeeds));

        ImmutableMap<Pair<Long, Boolean>, Double> invalidFootCustomSpeeds = ImmutableMap.of(Pair.of(1L, Boolean.TRUE), 15.0, Pair.of(2L, Boolean.FALSE), 0.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("foot_invalid", invalidFootCustomSpeeds));

        // validation should be vehicle-specific. speed is too high for trucks, but valid for cars
        ImmutableMap<Pair<Long, Boolean>, Double> validCarCustomSpeeds = invalidTruckCustomSpeeds;
        assertTrue(CustomSpeedsVehicle.validateCustomSpeeds("car_valid", validCarCustomSpeeds));

        // creating a CustomSpeedsVehicle with invalid speeds should be disallowed
        assertThrows(IllegalArgumentException.class, () -> CustomSpeedsVehicle.create("car_invalid", Pair.of(invalidCarCustomSpeeds, true)));
    }
}

package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CustomSpeedsVehicleTest {

    @Test
    public void testInvalidSpeeds() {
        ImmutableMap<Long, Double> invalidCarCustomSpeeds = ImmutableMap.of(1L, 200.0, 2L, 2.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("car_invalid", invalidCarCustomSpeeds));

        ImmutableMap<Long, Double> invalidSmallTruckCustomSpeeds = ImmutableMap.of(1L, 150.0, 2L, -1.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("small_truck_invalid", invalidSmallTruckCustomSpeeds));

        ImmutableMap<Long, Double> invalidTruckCustomSpeeds = ImmutableMap.of(1L, 100.0, 2L, 0.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("truck_invalid", invalidTruckCustomSpeeds));

        ImmutableMap<Long, Double> invalidBikeCustomSpeeds = ImmutableMap.of(1L, 35.0, 2L, 0.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("bike_invalid", invalidBikeCustomSpeeds));

        ImmutableMap<Long, Double> invalidFootCustomSpeeds = ImmutableMap.of(1L, 15.0, 2L, 0.0);
        assertFalse(CustomSpeedsVehicle.validateCustomSpeeds("foot_invalid", invalidFootCustomSpeeds));

        // validation should be vehicle-specific. speed is too high for trucks, but valid for cars
        ImmutableMap<Long, Double> validCarCustomSpeeds = invalidTruckCustomSpeeds;
        assertTrue(CustomSpeedsVehicle.validateCustomSpeeds("car_valid", validCarCustomSpeeds));

        // creating a CustomSpeedsVehicle with invalid speeds should be disallowed
        assertThrows(IllegalArgumentException.class, () -> CustomSpeedsVehicle.create("car_invalid", invalidCarCustomSpeeds));
    }
}

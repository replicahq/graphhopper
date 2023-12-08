package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class CustomSpeedsVehicleTest {

    @Test
    public void testInvalidSpeeds() {
        ImmutableMap<Long, Double> invalidCarCustomSpeeds = ImmutableMap.of(1L, 200.0, 2L, 2.0);
        assertThrows(IllegalArgumentException.class, () -> CustomSpeedsVehicle.create("car_invalid", invalidCarCustomSpeeds));

        ImmutableMap<Long, Double> invalidSmallTruckCustomSpeeds = ImmutableMap.of(1L, 150.0, 2L, -1.0);
        assertThrows(IllegalArgumentException.class, () -> CustomSpeedsVehicle.create("small_truck_invalid", invalidSmallTruckCustomSpeeds));

        ImmutableMap<Long, Double> invalidTruckCustomSpeeds = ImmutableMap.of(1L, 100.0, 2L, 0.0);
        assertThrows(IllegalArgumentException.class, () -> CustomSpeedsVehicle.create("truck_invalid", invalidSmallTruckCustomSpeeds));

        // validation should be vehicle-specific. speed is too high for trucks, but valid for cars
        ImmutableMap<Long, Double> validCarCustomSpeeds = invalidTruckCustomSpeeds;
        CustomSpeedsVehicle.create("car_valid", validCarCustomSpeeds);
    }
}

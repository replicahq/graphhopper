package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.graphhopper.config.Profile;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class CustomSpeedsUtilsTest {
    private static final ImmutableMap<Long, Double> TEST_CUSTOM_SPEEDS = ImmutableMap.of(1L, 2.0, 3L, 4.0, 123L, 45.6789);
    private static final ImmutableMap<Long, Double> FAST_THRUTON_DRIVE_SPEEDS = ImmutableMap.of(10485465L, 90.0);
    private static final ImmutableMap<Long, Double> BASELINE_ROAD_CLOSURE_SPEEDS = ImmutableMap.of(76254223L, 0.0);

    @Test
    public void testGetCustomSpeedVehiclesByName() {
        List<Profile> profiles = ImmutableList.of(
                createProfile("prof1", "car", null),
                createProfile("prof2", "car_custom", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof3", "car_custom_2", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv"),
                createProfile("prof4", "truck", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv"),
                createProfile("prof5", "small_truck", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof6", "foot", "../web/test-data/custom_speeds/baseline_road_zero_speed.csv"),
                createProfile("prof7", "bike", "../web/test-data/custom_speeds/baseline_road_zero_speed.csv")
        );
        ImmutableMap<String, CustomSpeedsVehicle> customSpeedVehiclesByName =
                CustomSpeedsUtils.getCustomSpeedVehiclesByName(profiles);
        ImmutableMap<String, CustomSpeedsVehicle> expectedCustomSpeedVehiclesByName = ImmutableMap.of(
                "car_custom", CustomSpeedsVehicle.create("car_custom", TEST_CUSTOM_SPEEDS),
                "car_custom_2", CustomSpeedsVehicle.create("car_custom_2", FAST_THRUTON_DRIVE_SPEEDS),
                "truck", CustomSpeedsVehicle.create("truck", FAST_THRUTON_DRIVE_SPEEDS),
                "small_truck", CustomSpeedsVehicle.create("small_truck", TEST_CUSTOM_SPEEDS),
                "foot", CustomSpeedsVehicle.create("foot", BASELINE_ROAD_CLOSURE_SPEEDS),
                "bike", CustomSpeedsVehicle.create("bike", BASELINE_ROAD_CLOSURE_SPEEDS));
        assertEquals(expectedCustomSpeedVehiclesByName, customSpeedVehiclesByName);
    }

    @Test
    public void testGetCustomSpeedVehiclesByNameUniqueVehicleNames() {
        // each vehicle may only be associated with one custom speed file
        List<Profile> invalidProfiles = ImmutableList.of(
                createProfile("prof1", "car_custom_speeds", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof2", "car_custom_speeds", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv")
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomSpeedsUtils.getCustomSpeedVehiclesByName(invalidProfiles));

        // vehicle/custom speed file pairing must be consistent
        List<Profile> invalidProfiles2 = ImmutableList.of(
                createProfile("prof1", "car_custom_speeds", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof2", "car_custom_speeds", null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomSpeedsUtils.getCustomSpeedVehiclesByName(invalidProfiles2));

        // vehicle can be reused between profiles if it has the same custom speed file
        List<Profile> validProfiles = ImmutableList.of(
                createProfile("prof1", "car_custom_speeds", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv"),
                createProfile("prof2", "car_custom_speeds", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv")
        );
        ImmutableMap<String, CustomSpeedsVehicle> customSpeedVehiclesByName =
                CustomSpeedsUtils.getCustomSpeedVehiclesByName(validProfiles);
        ImmutableMap<String, CustomSpeedsVehicle> expectedCustomSpeedVehiclesByName = ImmutableMap.of(
                "car_custom_speeds", CustomSpeedsVehicle.create("car_custom_speeds", FAST_THRUTON_DRIVE_SPEEDS));
        assertEquals(expectedCustomSpeedVehiclesByName, customSpeedVehiclesByName);
    }

    @Test
    public void testGetCustomSpeedVehiclesByNameUnsupportedBaseVehicle() {
        // custom speeds only currently support cars/trucks/small_trucks/foot/bike
        List<Profile> invalidProfiles = ImmutableList.of(
                createProfile("prof1", "ferry_custom", "../web/test-data/custom_speeds/test_custom_speeds.csv")
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomSpeedsUtils.getCustomSpeedVehiclesByName(invalidProfiles));

        // custom vehicle name must have base vehicle as prefix
        List<Profile> invalidProfiles2 = ImmutableList.of(
                createProfile("prof1", "custom_car", "../web/test-data/custom_speeds/test_custom_speeds.csv")
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomSpeedsUtils.getCustomSpeedVehiclesByName(invalidProfiles2));
    }

    private static Profile createProfile(String profileName, String vehicleName, @Nullable String customSpeedsFilePath) {
        Profile profile = new Profile(profileName);
        profile.setVehicle(vehicleName);
        if (customSpeedsFilePath != null) {
            profile.putHint(CustomSpeedsUtils.CUSTOM_SPEED_FILE_CONFIG_FIELD, customSpeedsFilePath);
        }
        return profile;
    }
}

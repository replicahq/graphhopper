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

    @Test
    public void testGetVehicleNameToCustomSpeeds() {
        List<Profile> profiles = ImmutableList.of(
                createProfile("prof1", "car", null),
                createProfile("prof2", "car_prof2", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof3", "car_prof3", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv")
        );
        ImmutableMap<String, ImmutableMap<Long, Double>> vehicleNameToCustomSpeeds =
                CustomSpeedsUtils.getVehicleNameToCustomSpeeds(profiles);
        ImmutableMap<String, ImmutableMap<Long, Double>> expectedVehicleNameToCustomSpeeds = ImmutableMap.of(
                "car_prof2", ImmutableMap.of(1L, 2.0, 3L, 4.0, 123L, 456.789),
                "car_prof3", ImmutableMap.of(10485465L, 1000.0)
        );
        assertEquals(expectedVehicleNameToCustomSpeeds, vehicleNameToCustomSpeeds);
    }

    @Test
    public void testGetVehicleNameToCustomSpeedsUniqueVehicleName() {
        // each vehicle may only be associated with one custom speed file
        List<Profile> invalidProfiles = ImmutableList.of(
                createProfile("prof1", "car_custom_speeds", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof2", "car_custom_speeds", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv")
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomSpeedsUtils.getVehicleNameToCustomSpeeds(invalidProfiles));

        // vehicle/custom speed file pairing must be consistent
        List<Profile> invalidProfiles2 = ImmutableList.of(
                createProfile("prof1", "car_custom_speeds", "../web/test-data/custom_speeds/test_custom_speeds.csv"),
                createProfile("prof2", "car_custom_speeds", null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomSpeedsUtils.getVehicleNameToCustomSpeeds(invalidProfiles2));

        // vehicle can be reused between profiles if it has the same custom speed file
        List<Profile> validProfiles = ImmutableList.of(
                createProfile("prof1", "car_custom_speeds", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv"),
                createProfile("prof2", "car_custom_speeds", "../web/test-data/custom_speeds/custom_fast_thurton_drive_speed.csv")
        );
        ImmutableMap<String, ImmutableMap<Long, Double>> vehicleNameToCustomSpeeds =
                CustomSpeedsUtils.getVehicleNameToCustomSpeeds(validProfiles);
        ImmutableMap<String, ImmutableMap<Long, Double>> expectedVehicleNameToCustomSpeeds = ImmutableMap.of(
                "car_custom_speeds", ImmutableMap.of(10485465L, 1000.0)
        );
        assertEquals(expectedVehicleNameToCustomSpeeds, vehicleNameToCustomSpeeds);
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

package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.config.Profile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomSpeeds {

    public static final String CUSTOM_SPEED_FILE_FIELD = "custom_speed_file";

    public static Map<String, String> getVehicleNameToCustomSpeedFileName(List<Profile> profiles) {
        Map<String, String> vehicleNameToCustomSpeedFileName = new HashMap<>();

        for (Profile profile : profiles) {
            String customSpeedFileName = profile.getHints().getString(CustomSpeeds.CUSTOM_SPEED_FILE_FIELD, "");
            if (customSpeedFileName.isEmpty()) {
                continue;
            }
            String vehicle = profile.getVehicle();
            String existingCustomSpeedFileName = vehicleNameToCustomSpeedFileName.get(vehicle);
            if (existingCustomSpeedFileName != null && !existingCustomSpeedFileName.equals(customSpeedFileName)) {
                throw new IllegalArgumentException("Vehicle " + vehicle + " cannot be associated with multiple custom speed files. Please use a unique vehicle name for each vehicle with custom speeds!");
            }
            vehicleNameToCustomSpeedFileName.put(vehicle, customSpeedFileName);
        }

        return ImmutableMap.copyOf(vehicleNameToCustomSpeedFileName);
    }
}

package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.config.Profile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomSpeedsUtils {

    public static final String CUSTOM_SPEED_FILE_CONFIG_FIELD = "custom_speed_file";
    private static final String CUSTOM_SPEED_OSM_WAY_ID_COL_NAME = "osm_id";
    private static final String CUSTOM_SPEED_MAX_SPEED_KPH_COL_NAME = "max_speed_kph";

    public static Map<String, File> getVehicleNameToCustomSpeedFile(List<Profile> profiles) {
        Map<String, File> vehicleNameToCustomSpeedFileName = new HashMap<>();

        for (Profile profile : profiles) {
            String customSpeedFilePath = profile.getHints().getString(CustomSpeedsUtils.CUSTOM_SPEED_FILE_CONFIG_FIELD, "");
            if (customSpeedFilePath.isEmpty()) {
                continue;
            }
            File customSpeedFile = new File(customSpeedFilePath);

            String vehicle = profile.getVehicle();
            File existingCustomSpeedFile = vehicleNameToCustomSpeedFileName.get(vehicle);
            if (existingCustomSpeedFile != null && !existingCustomSpeedFile.equals(customSpeedFile)) {
                throw new IllegalArgumentException("Vehicle " + vehicle + " cannot be associated with multiple " +
                        "custom speed files. Please use a unique vehicle name for each vehicle with custom speeds!");
            }
            vehicleNameToCustomSpeedFileName.put(vehicle, customSpeedFile);
        }

        return ImmutableMap.copyOf(vehicleNameToCustomSpeedFileName);
    }

    public static ImmutableMap<Long, Double> parseOsmWayIdToMaxSpeed(File customSpeedFile) throws IOException {
        ImmutableMap.Builder<Long, Double> osmWayIdToMaxSpeed = ImmutableMap.builder();

        Reader in = new FileReader(customSpeedFile);
        Iterable<CSVRecord> records = CSVFormat.Builder.create().setHeader().build().parse(in);
        for (CSVRecord record : records) {
            Long osmWayId = Long.parseLong(record.get(CUSTOM_SPEED_OSM_WAY_ID_COL_NAME));
            Double maxSpeed = Double.parseDouble(record.get(CUSTOM_SPEED_MAX_SPEED_KPH_COL_NAME));
            osmWayIdToMaxSpeed.put(osmWayId, maxSpeed);
        }

        return osmWayIdToMaxSpeed.build();
    }
}

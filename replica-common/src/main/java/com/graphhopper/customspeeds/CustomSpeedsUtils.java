package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.config.Profile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CustomSpeedsUtils {

    public static final String CUSTOM_SPEED_FILE_CONFIG_FIELD = "custom_speed_file";
    private static final String CUSTOM_SPEED_OSM_WAY_ID_COL_NAME = "osm_way_id";
    private static final String CUSTOM_SPEED_MAX_SPEED_KPH_COL_NAME = "max_speed_kph";

    /**
     * Parses the custom speed files for any profiles using custom speeds. Each vehicle must be associated with the same
     * custom speed file across all profiles.
     *
     * @param profiles list of profiles for which the custom speed mapping should be retrieved
     * @return map of vehicle name to mapping from OSM way id to the custom speed to use for that way, in kph. Vehicles
     * without custom speeds are omitted from the map.
     *
     * @throws IllegalArgumentException if any profiles associate the same vehicle with different custom speed files
     * @throws RuntimeException if any custom speed files could not be found or are not properly formatted
     */
    public static ImmutableMap<String, ImmutableMap<Long, Double>> getVehicleNameToCustomSpeeds(List<Profile> profiles) {
        Map<String, File> vehicleNameToCustomSpeedFile = getVehicleNameToCustomSpeedFile(profiles);
        return vehicleNameToCustomSpeedFile.entrySet()
                .stream()
                .collect(ImmutableMap.toImmutableMap(
                        Map.Entry::getKey,
                        entry -> CustomSpeedsUtils.parseOsmWayIdToMaxSpeed(entry.getValue())));
    }

    private static Map<String, File> getVehicleNameToCustomSpeedFile(List<Profile> profiles) {
        Map<String, Optional<File>> vehicleNameToCustomSpeedFileName = new HashMap<>();

        for (Profile profile : profiles) {
            String customSpeedFilePath =
                    profile.getHints().getString(CustomSpeedsUtils.CUSTOM_SPEED_FILE_CONFIG_FIELD, "");
            Optional<File> customSpeedFile = customSpeedFilePath.isEmpty() ?
                    Optional.empty() : Optional.of(new File(customSpeedFilePath));

            String vehicle = profile.getVehicle();
            Optional<File> existingCustomSpeedFile = vehicleNameToCustomSpeedFileName.get(vehicle);
            if (existingCustomSpeedFile != null && !existingCustomSpeedFile.equals(customSpeedFile)) {
                throw new IllegalArgumentException("Vehicle " + vehicle + " must be consistently associated with the " +
                        "same custom speed file. Please use a unique vehicle name for each vehicle with custom speeds!");
            }
            vehicleNameToCustomSpeedFileName.put(vehicle, customSpeedFile);
        }

        return vehicleNameToCustomSpeedFileName.entrySet().stream()
                .filter(entry -> entry.getValue().isPresent())
                .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    private static ImmutableMap<Long, Double> parseOsmWayIdToMaxSpeed(File customSpeedFile) {
        ImmutableMap.Builder<Long, Double> osmWayIdToMaxSpeed = ImmutableMap.builder();

        try {
            Reader in = new FileReader(customSpeedFile);
            // setHeader() with no args allows the column names to be automatically parsed from the first line of the
            // file
            CSVParser parser = CSVFormat.Builder.create().setHeader().build().parse(in);
            for (CSVRecord record : parser) {
                Long osmWayId = Long.parseLong(record.get(CUSTOM_SPEED_OSM_WAY_ID_COL_NAME));
                Double maxSpeed = Double.parseDouble(record.get(CUSTOM_SPEED_MAX_SPEED_KPH_COL_NAME));
                osmWayIdToMaxSpeed.put(osmWayId, maxSpeed);
            }
            parser.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse custom speed file at path " + customSpeedFile.getAbsolutePath()
                    + ". Please ensure file exists and is in the correct format!", e);
        }

        return osmWayIdToMaxSpeed.build();
    }
}
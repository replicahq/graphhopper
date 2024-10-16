package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.graphhopper.config.Profile;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.stream.Collectors;

public class CustomSpeedsUtils {

    public static final String CUSTOM_SPEED_FILE_CONFIG_FIELD = "custom_speed_file";
    private static final String CUSTOM_SPEED_OSM_WAY_ID_COL_NAME = "osm_way_id";
    private static final String CUSTOM_SPEED_MAX_SPEED_KPH_COL_NAME = "max_speed_kph";
    private static final String CUSTOM_SPEED_BWD_COL_NAME = "bwd";

    /**
     * Parses the custom speed files for any profiles using custom speeds. Each vehicle must be associated with the same
     * custom speed file across all profiles.
     *
     * @param profiles list of profiles for which the custom speed mapping should be retrieved
     * @return map of custom vehicle name to CustomSpeedsVehicle object containing the custom speeds mapping and the base
     * vehicle type. The speeds mapping associates each customized OSM way id to the speed to use for that way, in kph.
     * Vehicles without custom speeds are omitted from the map.
     *
     * @throws IllegalArgumentException if any profiles associate the same vehicle with different custom speed files,
     * if the base vehicle type for any custom speeds vehicles could not be determined, or if any custom speeds are invalid
     * @throws RuntimeException if any custom speed files could not be found or are not properly formatted
     */
    public static ImmutableMap<String, CustomSpeedsVehicle> getCustomSpeedVehiclesByName(List<Profile> profiles) {
        Map<String, File> vehicleNameToCustomSpeedFile = getVehicleNameToCustomSpeedFile(profiles);
        // wrap transformValues result in a HashMap to turn the live, lazy view into a traditional map
        Map<String, Pair<ImmutableMap<Pair<Long, Boolean>, Double>, Boolean>> vehicleNameToCustomSpeeds = new HashMap<>(
                Maps.transformValues(vehicleNameToCustomSpeedFile, CustomSpeedsUtils::parseOsmWayIdAndBwdToMaxSpeed));

        // validate all speeds before attempting to create CustomSpeedsVehicles so all invalid speeds will be logged
        // and users can address all errors instead of one-at-a-time
        Set<String> invalidSpeedVehicleNames = vehicleNameToCustomSpeeds.entrySet().stream()
                .filter(entry -> !CustomSpeedsVehicle.validateCustomSpeeds(entry.getKey(), entry.getValue().getLeft()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (!invalidSpeedVehicleNames.isEmpty()) {
            throw new IllegalArgumentException(String.format("Invalid custom speeds for vehicles %s. See logging for details", invalidSpeedVehicleNames));
        }

        return ImmutableMap.copyOf(Maps.transformEntries(vehicleNameToCustomSpeeds, CustomSpeedsVehicle::create));
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

    /**
     * Parses custom speeds from a single custom speeds file. Speeds are stored in a mapping from
     * (OSM Way ID, `bwd`) -> speed, where `bwd` is a flag denoting that the speed should be stored for the
     * backward direction of a Way. If no `bwd` column is present, speeds are stored for both True and False
     * values of `bwd` (ie, the speed is applied for both directions, where applicable).
     *
     * @param customSpeedFile input custom speed file to be parsed
     * @return Pair containing the mapping of (OSM Way Id, `bwd`) -> speed, and a boolean representing whether or not
     * the `bwd` column was present in the input file
     */
    private static Pair<ImmutableMap<Pair<Long, Boolean>, Double>, Boolean> parseOsmWayIdAndBwdToMaxSpeed(File customSpeedFile) {
        ImmutableMap.Builder<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed = ImmutableMap.builder();
        boolean bwdColumnPresent;
        try {
            Reader in = new FileReader(customSpeedFile);
            // setHeader() with no args allows the column names to be automatically parsed from the first line of the
            // file
            CSVParser parser = CSVFormat.Builder.create().setHeader().build().parse(in);
            bwdColumnPresent = parser.getHeaderNames().contains(CUSTOM_SPEED_BWD_COL_NAME);
            for (CSVRecord record : parser) {
                Long osmWayId = Long.parseLong(record.get(CUSTOM_SPEED_OSM_WAY_ID_COL_NAME));
                Double maxSpeed = Double.parseDouble(record.get(CUSTOM_SPEED_MAX_SPEED_KPH_COL_NAME));

                // If `bwd` column is present, use it to store directional speed for the Way
                if (bwdColumnPresent) {
                    Boolean bwd = Boolean.parseBoolean(record.get(CUSTOM_SPEED_BWD_COL_NAME));
                    osmWayIdAndBwdToMaxSpeed.put(Pair.of(osmWayId, bwd), maxSpeed);
                }
                // Otherwise, store the speed for the way in both directions
                else {
                    osmWayIdAndBwdToMaxSpeed.put(Pair.of(osmWayId, Boolean.TRUE), maxSpeed);
                    osmWayIdAndBwdToMaxSpeed.put(Pair.of(osmWayId, Boolean.FALSE), maxSpeed);
                }
            }
            parser.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse custom speed file at path " + customSpeedFile.getAbsolutePath()
                    + ". Please ensure file exists and is in the correct format!", e);
        }

        return Pair.of(osmWayIdAndBwdToMaxSpeed.build(), bwdColumnPresent);
    }

    public static Optional<Double> getCustomMaxSpeed(ReaderWay way, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToCustomMaxSpeed, boolean bwd) {
        // n.b. CarAverageSpeedParser sets max speed to be 90% of the OSM's max speed for the way, but we don't apply the 90%
        // discount for the custom speeds we've been explicitly given
        return Optional.ofNullable(osmWayIdAndBwdToCustomMaxSpeed.get(Pair.of(way.getId(), bwd)));
    }

    public static void validateCustomSpeedDirection(ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToMaxSpeed, boolean bwdColumnPresent,
                                                    long wayId, BooleanEncodedValue accessEnc, int ghEdgeId, EdgeIntAccess edgeIntAccess) {
        // If way ID -> custom speed mapping provided as input specifies a speed for the bwd direction of a road
        // that doesn't allow travel in the bwd direction, throw an error
        Pair<Long, Boolean> wayIdAndBwd = Pair.of(wayId, true);
        if (osmWayIdAndBwdToMaxSpeed.containsKey(wayIdAndBwd) && bwdColumnPresent && !accessEnc.getBool(true, ghEdgeId, edgeIntAccess)) {
            throw new RuntimeException("Input custom speeds specify a bwd speed for OSM Way " + wayId + ", but it doesn't allow travel in that direction!");
        }
    }
}

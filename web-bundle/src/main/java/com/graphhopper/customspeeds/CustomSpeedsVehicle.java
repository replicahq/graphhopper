package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.graphhopper.RouterConstants;
import com.graphhopper.http.TruckAverageSpeedParser;
import com.graphhopper.replica.ReplicaCustomSpeedsFootTagParser;
import com.graphhopper.routing.util.parsers.BikeAverageSpeedParser;
import com.graphhopper.routing.util.parsers.CarAverageSpeedParser;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CustomSpeedsVehicle {
    private static final Logger logger = LoggerFactory.getLogger(CustomSpeedsVehicle.class);

    public enum VehicleType {
        CAR(CarAverageSpeedParser.CAR_MAX_SPEED),
        TRUCK(TruckAverageSpeedParser.EE_TRUCK_MAX_SPEED),
        SMALL_TRUCK(TruckAverageSpeedParser.EE_SMALL_TRUCK_MAX_SPEED),
        BIKE(BikeAverageSpeedParser.MAX_SPEED),
        FOOT(ReplicaCustomSpeedsFootTagParser.FOOT_MAX_SPEED);

        private double maxValidSpeed;

        VehicleType(double maxValidSpeed) {
            this.maxValidSpeed = maxValidSpeed;
        }

        public double getMaxValidSpeed() {
            return this.maxValidSpeed;
        }
    }

    public final VehicleType baseVehicleType;
    public final String customVehicleName;
    public final ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToCustomSpeed;
    public final boolean directionalCustomSpeedsProvided;

    private CustomSpeedsVehicle(String customVehicleName, VehicleType baseVehicleType, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToCustomSpeed, boolean directionalCustomSpeedsProvided) {
        this.customVehicleName = customVehicleName;
        this.baseVehicleType = baseVehicleType;
        this.osmWayIdAndBwdToCustomSpeed = osmWayIdAndBwdToCustomSpeed;
        this.directionalCustomSpeedsProvided = directionalCustomSpeedsProvided;
    }

    public static CustomSpeedsVehicle create(String customVehicleName, Pair<ImmutableMap<Pair<Long, Boolean>, Double>, Boolean> osmWayIdAndBwdToCustomSpeed) {
        if (!validateCustomSpeeds(customVehicleName, osmWayIdAndBwdToCustomSpeed.getLeft())) {
            throw new IllegalArgumentException(String.format("Invalid speeds for vehicle %s. See logging for details", customVehicleName));
        }

        return new CustomSpeedsVehicle(customVehicleName, CustomSpeedsVehicle.getBaseVehicleType(customVehicleName), osmWayIdAndBwdToCustomSpeed.getLeft(), osmWayIdAndBwdToCustomSpeed.getRight());
    }

    public static boolean validateCustomSpeeds(String customVehicleName, ImmutableMap<Pair<Long, Boolean>, Double> osmWayIdAndBwdToCustomSpeed) {
        VehicleType baseVehicleType = CustomSpeedsVehicle.getBaseVehicleType(customVehicleName);

        // wrap filterValues result in a HashMap to turn live, lazy view into a traditional map
        Map<Pair<Long, Boolean>, Double> invalidSpeeds = new HashMap<>(Maps.filterValues(osmWayIdAndBwdToCustomSpeed,
                speed -> speed < 0 || speed > baseVehicleType.getMaxValidSpeed()));
        if (!invalidSpeeds.isEmpty()) {
            logger.error("Invalid speeds found for vehicle {}. Custom speeds for base vehicle {} must be between 0 and {}. Full mapping of invalid speeds: {}",
                    customVehicleName, baseVehicleType, baseVehicleType.getMaxValidSpeed(), invalidSpeeds);
            return false;
        }
        return true;
    }

    private static CustomSpeedsVehicle.VehicleType getBaseVehicleType(String customVehicleName) {
        if (customVehicleName.startsWith(RouterConstants.CAR_VEHICLE_NAME)) {
            return CustomSpeedsVehicle.VehicleType.CAR;
        }
        if (customVehicleName.startsWith(RouterConstants.TRUCK_VEHICLE_NAME)) {
            return CustomSpeedsVehicle.VehicleType.TRUCK;
        }
        if (customVehicleName.startsWith(RouterConstants.SMALL_TRUCK_VEHICLE_NAME)) {
            return CustomSpeedsVehicle.VehicleType.SMALL_TRUCK;
        }
        if (customVehicleName.startsWith(RouterConstants.BIKE_VEHICLE_NAME)) {
            return CustomSpeedsVehicle.VehicleType.BIKE;
        }
        if (customVehicleName.startsWith(RouterConstants.FOOT_VEHICLE_NAME)) {
            return CustomSpeedsVehicle.VehicleType.FOOT;
        }
        throw new IllegalArgumentException("Could not determine base vehicle type for custom speeds vehicle " + customVehicleName + ". Supported base vehicle types: " + EnumSet.allOf(CustomSpeedsVehicle.VehicleType.class));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomSpeedsVehicle that = (CustomSpeedsVehicle) o;
        return baseVehicleType == that.baseVehicleType && customVehicleName.equals(that.customVehicleName) && osmWayIdAndBwdToCustomSpeed.equals(that.osmWayIdAndBwdToCustomSpeed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseVehicleType, customVehicleName, osmWayIdAndBwdToCustomSpeed);
    }
}

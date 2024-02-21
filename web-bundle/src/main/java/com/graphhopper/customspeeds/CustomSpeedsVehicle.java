package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.graphhopper.RouterConstants;
import com.graphhopper.http.TruckAverageSpeedParser;
import com.graphhopper.routing.util.parsers.BikeAverageSpeedParser;
import com.graphhopper.routing.util.parsers.CarAverageSpeedParser;
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
        FOOT(RouterConstants.FOOT_MAX_SPEED);

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
    public final ImmutableMap<Long, Double> osmWayIdToCustomSpeed;

    private CustomSpeedsVehicle(String customVehicleName, VehicleType baseVehicleType, ImmutableMap<Long, Double> osmWayIdToCustomSpeed) {
        this.customVehicleName = customVehicleName;
        this.baseVehicleType = baseVehicleType;
        this.osmWayIdToCustomSpeed = osmWayIdToCustomSpeed;
    }

    public static CustomSpeedsVehicle create(String customVehicleName, ImmutableMap<Long, Double> osmWayIdToCustomSpeed) {
        if (!validateCustomSpeeds(customVehicleName, osmWayIdToCustomSpeed)) {
            throw new IllegalArgumentException(String.format("Invalid speeds for vehicle %s. See logging for details", customVehicleName));
        }

        return new CustomSpeedsVehicle(customVehicleName, CustomSpeedsVehicle.getBaseVehicleType(customVehicleName), osmWayIdToCustomSpeed);
    }

    public static boolean validateCustomSpeeds(String customVehicleName, ImmutableMap<Long, Double> osmWayIdToCustomSpeed) {
        VehicleType baseVehicleType = CustomSpeedsVehicle.getBaseVehicleType(customVehicleName);

        // wrap filterValues result in a HashMap to turn live, lazy view into a traditional map
        Map<Long, Double> invalidSpeeds = new HashMap<>(Maps.filterValues(osmWayIdToCustomSpeed,
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
        return baseVehicleType == that.baseVehicleType && customVehicleName.equals(that.customVehicleName) && osmWayIdToCustomSpeed.equals(that.osmWayIdToCustomSpeed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseVehicleType, customVehicleName, osmWayIdToCustomSpeed);
    }
}

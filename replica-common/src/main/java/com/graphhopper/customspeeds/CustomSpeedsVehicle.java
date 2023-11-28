package com.graphhopper.customspeeds;

import com.google.common.collect.ImmutableMap;
import com.graphhopper.RouterConstants;

import java.util.EnumSet;
import java.util.Objects;

public class CustomSpeedsVehicle {
    // TODO support custom speeds for bikes and pedestrians (RAD-6445, RAD-6446)
    public enum VehicleType {
        CAR,
        TRUCK,
        SMALL_TRUCK,
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
        return new CustomSpeedsVehicle(customVehicleName, CustomSpeedsVehicle.getBaseVehicleType(customVehicleName), osmWayIdToCustomSpeed);
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

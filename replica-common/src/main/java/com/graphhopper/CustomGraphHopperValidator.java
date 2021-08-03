package com.graphhopper;

import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.LockFactory;
import com.graphhopper.storage.NativeFSLockFactory;
import com.graphhopper.storage.index.LocationIndex;

public class CustomGraphHopperValidator extends GraphHopperGtfs {
    private boolean validate;

    // Copied from GraphHopper.java
    private final String fileLockName = "gh.lock";
    private LockFactory lockFactory = new NativeFSLockFactory();

    public CustomGraphHopperValidator(GraphHopperConfig ghConfig) {
        super(ghConfig);
        System.out.println(ghConfig.getString("validation", ""));
        // System.out.println(ghConfig.getBool("validation", false));
        this.validate = ghConfig.getString("validation", "").equals("true");
        System.out.println("validate is " + this.validate);
    }

    @Override
    protected void importPublicTransit() {
        if (validate) {
            super.importPublicTransit();
        }
    }

    @Override
    public void close() {
        if (validate) {
            getGtfsStorage().close();
        }

        // Remainder of method is copied directly from GraphHopper.close()
        GraphHopperStorage ghStorage = super.getGraphHopperStorage();
        LocationIndex locationIndex = super.getLocationIndex();
        if (ghStorage != null) {
            ghStorage.close();
        }
        if (locationIndex != null) {
            locationIndex.close();
        }

        try {
            lockFactory.forceRemove(fileLockName, true);
        } catch (Exception e) {
            // silently fail e.g. on Windows where we cannot remove an unreleased native lock
        }
    }
}

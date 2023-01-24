package com.graphhopper;

import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.storage.LockFactory;
import com.graphhopper.storage.NativeFSLockFactory;
import com.graphhopper.storage.RoutingCHGraph;

/***
 * Custom class used for "2-pass" GTFS validation; first pass with validate=False
 * imports just the OSM; second pass with validate=True imports the GTFS
 * todo: update to use new features that let you import OSM/GTFS in stages?
***/
public class CustomGraphHopperValidator extends GraphHopperGtfs {
    private boolean validate;

    // Copied from GraphHopper.java
    private final String fileLockName = "gh.lock";
    private LockFactory lockFactory = new NativeFSLockFactory();

    public CustomGraphHopperValidator(GraphHopperConfig ghConfig) {
        super(ghConfig);
        this.validate = ghConfig.getString("validation", "").equals("true");
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
        if (getBaseGraph() != null)
            getBaseGraph().close();
        if (getProperties() != null)
            getProperties().close();

        getCHGraphs().values().forEach(RoutingCHGraph::close);
        getLandmarks().values().forEach(LandmarkStorage::close);

        if (getLocationIndex() != null)
            getLocationIndex().close();

        try {
            lockFactory.forceRemove(fileLockName, true);
        } catch (Exception ex) {
            // silently fail e.g. on Windows where we cannot remove an unreleased native lock
        }
    }
}

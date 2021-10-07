package com.replica;

import com.graphhopper.util.Helper;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;

import static com.replica.ReplicaGraphHopperTest.*;
import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

public class ReplicaGraphHopperTestExtention implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static boolean started = false;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!started) {
            started = true;
            setup();
            // The following line registers a callback hook when the root test context is shut down
            context.getRoot().getStore(GLOBAL).put("Global GraphHopper test context", this);
        }
    }

    @Override
    public void close() {
        Helper.removeDir(new File(GRAPH_FILES_DIR));
        Helper.removeDir(new File(TRANSIT_DATA_DIR));
        closeGraphhopper();
    }
}

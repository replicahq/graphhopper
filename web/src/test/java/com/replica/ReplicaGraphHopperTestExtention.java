package com.replica;

import com.graphhopper.http.GraphHopperApplication;
import com.graphhopper.http.GraphHopperBundle;
import com.graphhopper.http.cli.ExportCommand;
import com.graphhopper.http.cli.GtfsLinkMapperCommand;
import com.graphhopper.http.cli.ImportCommand;
import com.graphhopper.util.Helper;
import io.dropwizard.cli.Cli;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.util.JarLocation;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReplicaGraphHopperTestExtention extends ReplicaGraphHopperTest
        implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static boolean started = false;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!started) {
            started = true;
            // Fresh target directory
            Helper.removeDir(new File(GRAPH_FILES_DIR));
            Helper.removeDir(new File(TRANSIT_DATA_DIR));

            // Setup necessary mock
            final JarLocation location = mock(JarLocation.class);
            when(location.getVersion()).thenReturn(Optional.of("1.0.0"));

            // Add commands you want to test
            bootstrap = new Bootstrap<>(new GraphHopperApplication());
            bootstrap.addBundle(new GraphHopperBundle());
            bootstrap.addCommand(new ImportCommand());
            bootstrap.addCommand(new ExportCommand());
            bootstrap.addCommand(new GtfsLinkMapperCommand());

            // Run commands to build graph and GTFS link mappings for test region
            cli = new Cli(location, bootstrap, System.out, System.err);
            cli.run("import", TEST_GRAPHHOPPER_CONFIG_PATH);
            cli.run("gtfs_links", TEST_GRAPHHOPPER_CONFIG_PATH);

            loadGraphhopper();
            // The following line registers a callback hook when the root test context is shut down
            context.getRoot().getStore(GLOBAL).put("Global GraphHopper test context", this);
        }
    }

    @Override
    public void close() {
        Helper.removeDir(new File(GRAPH_FILES_DIR));
        Helper.removeDir(new File(TRANSIT_DATA_DIR));
        graphHopperManaged.getGraphHopper().close();
    }
}

package com.graphhopper.http.cli;

import com.graphhopper.GraphHopper;
import com.graphhopper.http.GraphHopperManaged;
import com.graphhopper.http.GraphHopperServerConfiguration;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValidateGTFSCommand extends ConfiguredCommand<GraphHopperServerConfiguration> {
    private static final Logger logger = LoggerFactory.getLogger(ValidateGTFSCommand.class);

    public ValidateGTFSCommand() {
        super("validate-gtfs", "validates gtfs :)");
    }

    @Override
    protected void run(Bootstrap<GraphHopperServerConfiguration> bootstrap, Namespace namespace,
                       GraphHopperServerConfiguration configuration) {
        final GraphHopperManaged graphHopper = new GraphHopperManaged(configuration.getGraphHopperConfiguration(), bootstrap.getObjectMapper());
        GraphHopper gh = graphHopper.getGraphHopper();
        gh.importOrLoad();
        gh.close();
    }
}

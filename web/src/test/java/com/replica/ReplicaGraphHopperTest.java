package com.replica;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.Lists;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.http.GraphHopperApplication;
import com.graphhopper.http.GraphHopperBundle;
import com.graphhopper.http.GraphHopperManaged;
import com.graphhopper.http.GraphHopperServerConfiguration;
import com.graphhopper.http.cli.ExportNationwideCommand;
import com.graphhopper.http.cli.GtfsLinkMapperCommand;
import com.graphhopper.http.cli.ImportCommand;
import com.graphhopper.jackson.GraphHopperConfigModule;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.util.Helper;
import io.dropwizard.cli.Cli;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.util.JarLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class ReplicaGraphHopperTest {
    private static final Logger logger = LoggerFactory.getLogger(ReplicaGraphHopperTest.class);
    protected static final String GRAPH_FILES_DIR = "transit_data/graphhopper/";
    protected static final String EXPORT_FILES_DIR = "transit_data/export_test/graphhopper/";
    protected static final String TRANSIT_DATA_DIR = "transit_data/";
    protected static final String TEST_REGION_NAME = "mini_nor_cal";
    protected static final String TEST_RELEASE_NAME = "graphhopper_test";
    protected static final String TEST_GRAPHHOPPER_CONFIG_PATH = "../configs/test_gh_config.yaml";
    protected static final String TEST_EXPORT_GRAPHHOPPER_CONFIG_PATH = "../configs/test_export_gh_config.yaml";
    protected static final List<String> TEST_GTFS_FILE_NAMES = parseTestGtfsFileNames();

    protected static Bootstrap<GraphHopperServerConfiguration> bootstrap;
    protected static Cli cli;
    protected static GraphHopperConfig graphHopperConfiguration = null;
    protected static GraphHopperManaged graphHopperManaged = null;

    private static List<String> parseTestGtfsFileNames() {
        List<String> fileNameList = Lists.newArrayList();
        try (BufferedReader br = new BufferedReader(new FileReader("test-data/test_gtfs_files.txt"))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Grab the base filename, ditching the .zip extension
                fileNameList.add(line.split("\\.")[0]);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            logger.error("Error reading file containing list of test GTFS feeds, test_gtfs_files.txt! " +
                    "Check that file exists in your /web/test-data directory and is formatted correctly");
        }
        return fileNameList;
    }

    protected static GraphHopperConfig loadGhConfig(String configPath) throws Exception {
        ObjectMapper yaml = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
        yaml.registerModule(new GraphHopperConfigModule());
        JsonNode yamlNode = yaml.readTree(new File(configPath));
        return yaml.convertValue(yamlNode.get("graphhopper"), GraphHopperConfig.class);
    }

    protected static void loadGraphhopper() throws Exception {
        loadGraphhopper(TEST_GRAPHHOPPER_CONFIG_PATH);
    }

    protected static void loadGraphhopper(String configPath) throws Exception {
        graphHopperConfiguration = loadGhConfig(configPath);
        ObjectMapper json = Jackson.newObjectMapper();
        graphHopperManaged = new GraphHopperManaged(graphHopperConfiguration, json);
        graphHopperManaged.start();
    }

    public static void setup() throws Exception {
        setup(TEST_GRAPHHOPPER_CONFIG_PATH, GRAPH_FILES_DIR);
    }

    public static void setup(String configPath, String graphDir) throws Exception {
        // Fresh target directory
        Helper.removeDir(new File(graphDir));
        Helper.removeDir(new File(TRANSIT_DATA_DIR));

        // Setup necessary mock
        final JarLocation location = mock(JarLocation.class);
        when(location.getVersion()).thenReturn(Optional.of("1.0.0"));

        // Add commands you want to test
        bootstrap = new Bootstrap<>(new GraphHopperApplication());
        bootstrap.addBundle(new GraphHopperBundle());
        bootstrap.addCommand(new ImportCommand());
        bootstrap.addCommand(new ExportNationwideCommand());
        bootstrap.addCommand(new GtfsLinkMapperCommand());

        // Run commands to build graph and GTFS link mappings for test region
        cli = new Cli(location, bootstrap, System.out, System.err);
        cli.run("export-nationwide", TEST_EXPORT_GRAPHHOPPER_CONFIG_PATH);
        cli.run("import", configPath);
        cli.run("gtfs_links", configPath);

        loadGraphhopper(configPath);
    }

    public static void closeGraphhopper() {
        graphHopperManaged.getGraphHopper().close();
    }
}

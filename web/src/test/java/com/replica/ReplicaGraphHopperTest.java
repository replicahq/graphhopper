package com.replica;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.Lists;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.http.GraphHopperManaged;
import com.graphhopper.http.GraphHopperServerConfiguration;
import com.graphhopper.jackson.GraphHopperConfigModule;
import com.graphhopper.jackson.Jackson;
import io.dropwizard.cli.Cli;
import io.dropwizard.setup.Bootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;


public class ReplicaGraphHopperTest {
    private static final Logger logger = LoggerFactory.getLogger(ReplicaGraphHopperTest.class);
    protected static final String GRAPH_FILES_DIR = "transit_data/graphhopper/";
    protected static final String TRANSIT_DATA_DIR = "transit_data/";
    protected static final String TEST_GRAPHHOPPER_CONFIG_PATH = "../test_gh_config.yaml";
    protected static final String TEST_REGION_NAME = "mini_nor_cal";
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
        System.out.println(fileNameList);
        return fileNameList;
    }

    protected static void loadGraphhopper() throws Exception {
        ObjectMapper yaml = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
        yaml.registerModule(new GraphHopperConfigModule());
        JsonNode yamlNode = yaml.readTree(new File(TEST_GRAPHHOPPER_CONFIG_PATH));
        graphHopperConfiguration = yaml.convertValue(yamlNode.get("graphhopper"), GraphHopperConfig.class);
        ObjectMapper json = Jackson.newObjectMapper();
        graphHopperManaged = new GraphHopperManaged(graphHopperConfiguration, json);
        graphHopperManaged.start();
    }
}

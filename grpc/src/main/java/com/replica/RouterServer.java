/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.replica;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.*;
import com.graphhopper.http.GraphHopperManaged;
import com.graphhopper.jackson.Jackson;
import com.timgroup.statsd.NonBlockingStatsDClientBuilder;
import com.timgroup.statsd.StatsDClient;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.bundles.redirect.PathRedirect;
import io.dropwizard.bundles.redirect.RedirectBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.grpc.Server;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel;
import io.grpc.netty.shaded.io.netty.util.concurrent.EventExecutor;
import io.grpc.netty.shaded.io.netty.util.concurrent.SingleThreadEventExecutor;
import io.grpc.netty.shaded.io.netty.util.concurrent.ThreadProperties;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpcweb.GrpcPortNumRelay;
import io.grpcweb.GrpcWebTrafficServlet;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RouterServer {

    private static final Logger logger = LoggerFactory.getLogger(RouterServer.class);
    private Server server;
    private String configPath;
    private Map<String, Integer> defaultProperties;
    private Map<String, Integer> userDefinedProperties;
    private String regionName;
    private String releaseName;
    private GraphHopperManaged graphHopperManaged;
    public static final Set<String> SETTABLE_PARAMETERS = Sets.newHashSet(
            "SERVER_THREADS",
            "BOSS_EVENT_LOOP_THREADS",
            "WORKER_EVENT_LOOP_THREADS",
            "CONN_TIME_MAX_AGE_SECS",
            "CONN_TIME_GRACE_PERIOD_SECS",
            "MAX_CONC_CALLS_PER_CONN",
            "KEEP_ALIVE_TIME_SECS",
            "KEEP_ALIVE_TIMEOUT_SECS",
            "FLOW_CONTROL_WINDOW_BYTES"
    );

    public RouterServer(String configPath, Map<String, Integer> defaultProperties,
                        Map<String, Integer> userDefinedProperties, String regionName, String releaseName) {
        this.configPath = configPath;
        this.defaultProperties = defaultProperties;
        this.userDefinedProperties = userDefinedProperties;
        this.regionName = regionName;
        this.releaseName = releaseName;
    }

    private void start() throws Exception {
        // Start GH instance based on config given as command-line arg
        ObjectMapper yaml = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
        JsonNode yamlNode = yaml.readTree(new File(configPath));
        GraphHopperConfig graphHopperConfiguration = yaml.convertValue(yamlNode.get("graphhopper"), GraphHopperConfig.class);
        graphHopperManaged = new GraphHopperManaged(graphHopperConfiguration);
        graphHopperManaged.start();

        // Grab instances of auto/bike/ped router and PT router (if applicable)
        GraphHopper graphHopper = graphHopperManaged.getGraphHopper();
        PtRouter ptRouter = null;
        if (graphHopper instanceof GraphHopperGtfs) {
            ptRouter = new PtRouterTripBasedImpl(graphHopper, graphHopperConfiguration, graphHopper.getTranslationMap(), graphHopper.getBaseGraph(), graphHopper.getEncodingManager(), graphHopper.getLocationIndex(), ((GraphHopperGtfs) graphHopper).getGtfsStorage(), graphHopper.getPathDetailsBuilderFactory());
        }

        // Load GTFS link mapping and GTFS info maps for use in building responses
        Map<String, String> gtfsLinkMappings = null;
        Map<String, List<String>> gtfsRouteInfo = null;
        Map<String, String> gtfsFeedIdMapping = null;

        File linkMappingsDbFile = new File("transit_data/gtfs_link_mappings/gtfs_link_mappings.db");
        if (linkMappingsDbFile.exists()) {
            DB db = DBMaker.newFileDB(linkMappingsDbFile).readOnly().make();
            gtfsLinkMappings = db.getHashMap("gtfsLinkMappings");
            gtfsRouteInfo = db.getHashMap("gtfsRouteInfo");
            gtfsFeedIdMapping = db.getHashMap("gtfsFeedIdMap");
            logger.info("Done loading GTFS link mappings and route info. Total number of mappings: " + gtfsLinkMappings.size());
        } else {
            logger.info("No GTFS link mapping mapdb file found! Skipped loading GTFS link mappings.");
        }


        NioEventLoopGroup workerEventLoopGroup = new NioEventLoopGroup(userDefinedProperties.getOrDefault("WORKER_EVENT_LOOP_THREADS", defaultProperties.get("WORKER_EVENT_LOOP_THREADS")));
        NioEventLoopGroup bossEventLoopGroup = new NioEventLoopGroup(userDefinedProperties.getOrDefault("BOSS_EVENT_LOOP_THREADS", defaultProperties.get("BOSS_EVENT_LOOP_THREADS")));

        String metricsHost = System.getenv("METRICS_AGENT_HOST");
        Optional<StatsDClient> maybeStatsDClient = Optional.empty();
        if (metricsHost != null) {
            // Initialize Metrics client
            StatsDClient statsDClient = new NonBlockingStatsDClientBuilder()
                    .hostname(metricsHost)
                    .port(8125)
                    .build();
            maybeStatsDClient = Optional.of(statsDClient);

            // Uncomment below block to turn on logging about GRPC queueing activity
            /*
            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
            logger.info("Scheduling networking metrics collection");
            exec.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    recordNetworkingMetrics(statsDClient, workerEventLoopGroup, "worker");
                    recordNetworkingMetrics(statsDClient, bossEventLoopGroup, "boss");
                }
            }, 0, 60, TimeUnit.SECONDS);
            */
        }

        logger.info("Metrics agent host IP is: " + metricsHost);

        // Start server
        int grpcPort = 50051;
        server = NettyServerBuilder.forPort(grpcPort)
                .addService(new RouterImpl(graphHopper, ptRouter, gtfsLinkMappings, gtfsRouteInfo, gtfsFeedIdMapping, maybeStatsDClient.orElse(null), regionName, releaseName))
                .addService(ProtoReflectionService.newInstance())
                .maxConnectionAge(userDefinedProperties.getOrDefault("CONN_TIME_MAX_AGE_SECS", defaultProperties.get("CONN_TIME_MAX_AGE_SECS")), TimeUnit.SECONDS)
                .maxConnectionAgeGrace(userDefinedProperties.getOrDefault("CONN_TIME_GRACE_PERIOD_SECS", defaultProperties.get("CONN_TIME_GRACE_PERIOD_SECS")), TimeUnit.SECONDS)
                .executor(Executors.newFixedThreadPool(userDefinedProperties.getOrDefault("SERVER_THREADS", defaultProperties.get("SERVER_THREADS"))))
                .workerEventLoopGroup(workerEventLoopGroup)
                .bossEventLoopGroup(bossEventLoopGroup)
                .channelType(NioServerSocketChannel.class)
                .keepAliveTime(userDefinedProperties.getOrDefault("KEEP_ALIVE_TIME_SECS", defaultProperties.get("KEEP_ALIVE_TIME_SECS")), TimeUnit.SECONDS)
                .keepAliveTimeout(userDefinedProperties.getOrDefault("KEEP_ALIVE_TIMEOUT_SECS", defaultProperties.get("KEEP_ALIVE_TIMEOUT_SECS")), TimeUnit.SECONDS)
                .flowControlWindow(userDefinedProperties.getOrDefault("FLOW_CONTROL_WINDOW_BYTES", defaultProperties.get("FLOW_CONTROL_WINDOW_BYTES")))
                .build()
                .start();

        logger.info("Started server with the following user-provided properties: " + userDefinedProperties);
        logger.info("All other properties utilize the default values: " + defaultProperties);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                graphHopperManaged.stop();
                RouterServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("*** server shut down");
        }));

        // Start the grpc-web proxy on grpc-web-port.
        new MyApplication().run("server", "config-proxy.yaml");
        logger.info("Started grpc-web proxy server");

        // grpc-web proxy needs to know the grpc-port# so it could connect to the grpc service.
        GrpcPortNumRelay.setGrpcPortNum(grpcPort);

    }

    // inspired by https://stackoverflow.com/questions/32933367/monitoring-the-size-of-the-netty-event-loop-queues and
    // https://github.com/mpusher/mpush/blob/f8d5c97f30b2bde12f1b60d11709ae1b75587a2e/mpush-tools/src/main/java/com/mpush/tools/Utils.java#L213
    public static void recordNetworkingMetrics(final StatsDClient statsDClient, final EventLoopGroup elg, final String componentName) {
        logger.info("Recording networking metrics");
        int index = 0;
        int poolSize = 0, queueSize = 0, activeCount = 0;
        for (final EventExecutor eventExecutor : elg) {
            poolSize++;
            if (eventExecutor instanceof SingleThreadEventExecutor) {
                final SingleThreadEventExecutor singleExecutor = (SingleThreadEventExecutor) eventExecutor;
                String metricName = "EventLoopGroup_" + componentName +  "_pending_tasks";
                int pendingTasks = singleExecutor.pendingTasks();
                statsDClient.gauge(metricName, pendingTasks, Integer.toString(index));
                index++;

                queueSize += pendingTasks;
                ThreadProperties tp = singleExecutor.threadProperties();
                if (tp.state() == Thread.State.RUNNABLE) {
                    activeCount++;
                }
            }
        }
        logger.info(componentName + " pool size: " + poolSize + " ; queue size: " + queueSize + " ; active count: " + activeCount);
        statsDClient.gauge(componentName + "_pool_size", poolSize);
        statsDClient.gauge(componentName + "_queue_size", queueSize);
        statsDClient.gauge(componentName + "_active_count", activeCount);
    }

    private void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main launches the server from the command line.
     *
     * Required arg:
     * GH config file
     *
     * Optional parameters, specified in RouterServer.SETTABLE_PARAMETERS, are passed in by being set as
     * environment variables; for any parameters not provided by the user in this fashion, the defaults
     * specified in the makeDefaultPropertiesMap() function below will be used instead.
     *
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new RuntimeException("Must include path to GH config in order to start server!");
        }
        String config = args[0];

        final Map<String, Integer> defaultProperties = makeDefaultPropertiesMap();
        Map<String, Integer> userProvidedProperties = Maps.newHashMap();
        Map<String, String> envVars = System.getenv();
        for (String key: envVars.keySet()) {
            if (RouterServer.SETTABLE_PARAMETERS.contains(key)) {
                userProvidedProperties.put(key, Integer.parseInt(envVars.get(key)));
            }
        }

        String regionName = null;
        if (envVars.containsKey("REGION_NAME")) {
            regionName = envVars.get("REGION_NAME");
        }
        String releaseName = null;
        if (envVars.containsKey("HELM_RELEASE_NAME")) {
            releaseName = envVars.get("HELM_RELEASE_NAME");
        }

        final RouterServer server = new RouterServer(config, defaultProperties, userProvidedProperties, regionName, releaseName);
        server.start();
        server.blockUntilShutdown();
    }

    private static class MyApplication extends Application<MyConfiguration> {
        @Override
        public void initialize(Bootstrap bootstrap) {
            bootstrap.addBundle(new AssetsBundle("/assets/", "/maps/", "index.html", "maps"));
            bootstrap.addBundle(new AssetsBundle("/META-INF/resources/webjars", "/webjars", "index.html", "webjars"));
            bootstrap.addBundle(new RedirectBundle(new PathRedirect("/maps/pt", "/maps/pt/")));
        }

        @Override
        public void run(MyConfiguration configuration, Environment environment) throws Exception {
            environment.servlets().addServlet("grpc-web", GrpcWebTrafficServlet.class).addMapping("/api/*");
        }
    }

    private static Map<String, Integer> makeDefaultPropertiesMap() {
        Map<String, Integer> defaultProperties = Maps.newHashMap();
        defaultProperties.put("SERVER_THREADS", 3);
        defaultProperties.put("BOSS_EVENT_LOOP_THREADS", 1);
        defaultProperties.put("WORKER_EVENT_LOOP_THREADS", 2);
        defaultProperties.put("CONN_TIME_MAX_AGE_SECS", 120);
        defaultProperties.put("CONN_TIME_GRACE_PERIOD_SECS", 60);
        defaultProperties.put("MAX_CONC_CALLS_PER_CONN", 500);
        // Defaults for below settings are all GRPC defaults
        defaultProperties.put("KEEP_ALIVE_TIME_SECS", (int) (GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIME_NANOS * 1e-9));
        defaultProperties.put("KEEP_ALIVE_TIMEOUT_SECS", (int) (GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS * 1e-9));
        defaultProperties.put("FLOW_CONTROL_WINDOW_BYTES", NettyServerBuilder.DEFAULT_FLOW_CONTROL_WINDOW);
        return defaultProperties;
    }
}

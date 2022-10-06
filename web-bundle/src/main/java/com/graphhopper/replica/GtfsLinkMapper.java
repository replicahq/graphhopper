package com.graphhopper.replica;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.google.common.collect.*;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.GtfsStorage;
import com.graphhopper.util.details.PathDetail;
import org.apache.commons.lang3.tuple.Pair;
import org.mapdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class GtfsLinkMapper {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;
    private final String CSV_COLUMN_HEADERS = "route_id,feed_id,stop_id,next_stop_id," +
            "stop_lat,stop_lon,stop_lat_next,stop_lon_next,street_edges,transit_edge";
    private final String GTFS_LINK_MAPPING_DIR = "transit_data/gtfs_link_mappings/";

    public GtfsLinkMapper(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    public void setGtfsLinkMappings() {
        logger.info("Starting GTFS link mapping process");
        GtfsStorage gtfsStorage = ((GraphHopperGtfs) graphHopper).getGtfsStorage();
        Map<String, GTFSFeed> gtfsFeedMap = gtfsStorage.getGtfsFeeds();

        // Initialize mapdb database to store link mappings and route info
        logger.info("Initializing new mapdb file to store link mappings");
        File linkMappingdirectory = new File(GTFS_LINK_MAPPING_DIR);
        if (!linkMappingdirectory.exists()){
            linkMappingdirectory.mkdir();
        }
        DB db = DBMaker.newFileDB(new File(GTFS_LINK_MAPPING_DIR + "gtfs_link_mappings.db")).make();

        // These should be safe for parallel writes; from HTreeMap doc[1]:
        //     > It is thread safe, and supports parallel writes by using multiple segments, each with separate ReadWriteLock.
        //
        // 1: https://jankotek.gitbooks.io/mapdb/content/htreemap/
        HTreeMap<String, String> gtfsLinkMappings = db
                .createHashMap("gtfsLinkMappings")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.STRING)
                .make();

        HTreeMap<String, List<String>> gtfsRouteInfo = db
                .createHashMap("gtfsRouteInfo")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.JAVA)
                .make();

        HTreeMap<String, String> gtfsFeedIdMap = db
                .createHashMap("gtfsFeedIdMap")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.STRING)
                .make();

        // For testing
        // Set<String> allStableIds = Sets.newHashSet();

        // For each GTFS feed, pull out all stop pairs for trips on GTFS routes that travel on the street network, route
        // each pair via car, and store the returned IDs
        List<String> gtfsLinkMappingCsvRows = gtfsFeedMap.entrySet().stream().flatMap(feedEntry -> {
            String feedId = feedEntry.getKey();
            GTFSFeed feed = feedEntry.getValue();
            logger.info("Processing GTFS feed " + feed.feedId);

            // Record mapping of internal GH feed ID -> GTFS feed ID
            gtfsFeedIdMap.put(feedId, feed.feedId);

            // Store route information in db for _every_ route type
            Map<String, List<String>> routeInfoMap = feed.routes.keySet().stream()
                    .map(routeId -> feed.routes.get(routeId))
                    .collect(Collectors.toMap(
                            route -> feedId + ":" + route.route_id,
                            route -> getRouteInfo(route, feed.agency.get(route.agency_id).agency_name)
                    ));
            gtfsRouteInfo.putAll(routeInfoMap);

            SetMultimap<String, Pair<Stop, Stop>> routeIdToStopPairs = GtfsLinkMapperHelper.extractStopPairsFromFeed(feed);

            // We only care to track the unique stop->stop pairs for each route (ignoring trips).
            Set<Pair<Stop, Stop>> uniqueStopPairs = Sets.newHashSet(routeIdToStopPairs.values());

            AtomicInteger pairCountAtomic = new AtomicInteger();
            AtomicInteger routeNotFoundCountAtomic = new AtomicInteger();

            // Route a car between each stop->stop pair, and store the returned stable edge IDs in mapdb map
            uniqueStopPairs.parallelStream().forEach(stopPair -> {
                Stop stop = stopPair.getLeft();
                Stop nextStop = stopPair.getRight();

                int pairCount = pairCountAtomic.incrementAndGet();
                boolean shouldLog = (
                        uniqueStopPairs.size() > 10 &&
                        pairCount % (uniqueStopPairs.size() / 10) == 0
                );
                if (shouldLog) {
                    logger.info("Processed ~" + pairCount + "/" + uniqueStopPairs.size() + " stop pairs so far for feed " + feed.feedId);
                };

                // Form stop->stop auto routing requests and request a route
                GHRequest odRequest = new GHRequest(
                        stop.stop_lat, stop.stop_lon,
                        nextStop.stop_lat, nextStop.stop_lon
                );
                odRequest.setProfile("car_local");
                odRequest.setPathDetails(Lists.newArrayList("stable_edge_ids"));
                GHResponse response = graphHopper.route(odRequest);

                // If stop->stop path couldn't be found by GH, don't store anything
                if (response.getAll().size() == 0 || response.getAll().get(0).hasErrors()) {
                    routeNotFoundCountAtomic.incrementAndGet();
                    return;
                }

                // Parse stable IDs for each edge from response
                List<PathDetail> responsePathEdgeIdDetails = response.getAll().get(0)
                        .getPathDetails().get("stable_edge_ids");
                List<String> pathEdgeIds = responsePathEdgeIdDetails.stream()
                        .map(pathDetail -> (String) pathDetail.getValue())
                        .collect(Collectors.toList());
                // allStableIds.addAll(pathEdgeIds);

                // Merge all path IDs into String to use as value for gtfs link map
                String pathStableEdgeIdString = pathEdgeIds.stream().collect(Collectors.joining(","));
                gtfsLinkMappings.put(formatStopIds(stop, nextStop), pathStableEdgeIdString);
            });
            logger.info("Done processing GTFS feed " + feed.feedId + "; " + uniqueStopPairs.size() +
                    " total stop pairs processed; routes for " + routeNotFoundCountAtomic.get() +
                    " stop->stop pairs were not found");

            return getGtfsLinkCsvRowsForFeed(routeIdToStopPairs, gtfsLinkMappings).stream();
        }).sorted().collect(Collectors.toList());

        db.commit();
        db.close();
        logger.info("Done creating GTFS link mappings for " + gtfsFeedMap.size() + " GTFS feeds");

        writeGtfsLinksToCsv(gtfsLinkMappingCsvRows, new File(graphHopper.getGraphHopperLocation() + "/gtfs_link_mapping.csv"));

        // For testing
        // logger.info("All stable edge IDs: ");
        // logger.info(allStableIds.stream().collect(Collectors.joining(",")));
    }

    private String formatStopIds(Stop stop, Stop nextStop) {
        return stop.feed_id + ":" + stop.stop_id + "," + nextStop.stop_id;
    }

    // Ordered list of strings: agency_name,route_short_name,route_long_name,route_type
    private static List<String> getRouteInfo(Route route, String agencyName) {
        return Lists.newArrayList(agencyName, route.route_short_name, route.route_long_name, "" + route.route_type);
    }

    // returns all CSV rows (as a list of Strings) derived from a single GTFS feed's data
    private List<String> getGtfsLinkCsvRowsForFeed(SetMultimap<String, Pair<Stop, Stop>> routeIdToStopPairs,
                                                   HTreeMap<String, String> gtfsLinkMappings) {
        List<String> rowsForFeed = Lists.newArrayList();
        routeIdToStopPairs.entries().stream()
            .forEach(entry -> {
                String routeId = entry.getKey();
                Pair<Stop, Stop> stopPair = entry.getValue();
                Stop stop = stopPair.getLeft();
                Stop nextStop = stopPair.getRight();
                String stopPairString = formatStopIds(stop, nextStop);

                // Skip stop-stop pairs where we couldn't find a valid route
                if (!gtfsLinkMappings.containsKey(stopPairString)) {
                    return;
                }
                List<String> stableEdgeIds = Lists.newArrayList(gtfsLinkMappings.get(stopPairString).split(","));
                String stableEdgeIdString = stableEdgeIds.size() == 0 ? ""
                        : String.format("\"[%s]\"", stableEdgeIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")));

                // format: "{feed_id}:{route_id}/{feed_id}:{stop_id}/{feed_id}:{next_stop_id}"
                String transitEdgeString = stop.feed_id + ":" + routeId + "/" + stop.feed_id + ":"
                        + stop.stop_id + "/" + stop.feed_id + ":" + nextStop.stop_id;

                rowsForFeed.add(getCsvLine(routeId, stop.feed_id, stop.stop_id, nextStop.stop_id,
                        stop.stop_lat, stop.stop_lon, nextStop.stop_lat, nextStop.stop_lon,
                        stableEdgeIdString, transitEdgeString));
            });
        return rowsForFeed;
    }

    private static String getCsvLine(String routeId, String feedId, String stopId, String nextStopId,
                                     double stopLat, double stopLon, double stopLatNext, double stopLonNext,
                                     String stableEdgeIdString, String transitEdgeString) {
        return String.format("%s,%s,%s,%s,%f,%f,%f,%f,%s,%s", routeId, feedId, stopId, nextStopId,
                stopLat, stopLon, stopLatNext, stopLonNext, stableEdgeIdString, transitEdgeString
        );
    }

    // writes all pre-formed CSV rows to file
    private void writeGtfsLinksToCsv(List<String> gtfsLinkMappingCsvRows, File outputFile) {
        logger.info("Writing GTFS link mapping CSV file to " + outputFile.getPath() + "...");
        OutputStream outputStream;
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        PrintStream printStream = new PrintStream(outputStream);
        printStream.println(CSV_COLUMN_HEADERS);

        for (String row : gtfsLinkMappingCsvRows) {
            printStream.println(row);
        }

        printStream.close();
        logger.info("Done writing GTFS link mappings to CSV");
        if (!outputFile.exists()) {
            logger.error("Output file can't be found! CSV write may not have completed successfully");
        }
    }
}

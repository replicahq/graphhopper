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

import com.google.common.collect.*;
import com.google.protobuf.Timestamp;
import com.graphhopper.GraphHopper;
import com.graphhopper.ReplicaPathDetails;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.PtRouterTripBasedImpl;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import router.RouterOuterClass;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the entire server, not the server implementation itself, so that the plugging-together
 * of stuff (which is different for PT than for the rest) is under test, too.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
@ExtendWith({ReplicaGraphHopperTestExtention.class})
public class RouterServerTest extends ReplicaGraphHopperTest {
    // Departure time + ODs are chosen for micro_nor_cal test area, with a valid routing date of
    // 2019-10-15, and a bbox of -122.41229018000416,-120.49584285533076,37.75738096439945,39.52415953258036

    private static final Timestamp EARLIEST_DEPARTURE_TIME =
            Timestamp.newBuilder().setSeconds(Instant.parse("2019-10-15T13:30:00Z").toEpochMilli() / 1000).build();
    private static final double[] REQUEST_ORIGIN_1 = {38.74891667931467, -121.29023848101498}; // Roseville area
    private static final double[] REQUEST_ORIGIN_2 = {38.59337420024281, -121.48746937746185}; // Sacramento area
    private static final double[] REQUEST_DESTINATION_1 = {38.55518457319914, -121.43714698730038}; // Sacramento area
    private static final double[] REQUEST_DESTINATION_2 = {38.69871256445126, -121.27320348867218}; // South of Roseville

    // Should force a transfer between routes from 2 distinct feeds
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_DIFF_FEEDS = createPtRequest(REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    // Should force a transfer between routes from the same feed
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_SAME_FEED = createPtRequest(REQUEST_ORIGIN_2, REQUEST_DESTINATION_1);
    // Tests park-and-ride routing, with custom access/egress modes
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_PARK_N_RIDE = createPtRequest(REQUEST_ORIGIN_1, REQUEST_DESTINATION_1, "car", "foot");
    // Tests park-and-ride routing for a longer route (with a transfer)
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_PARK_N_RIDE_W_TRANSFER = createPtRequest(REQUEST_ORIGIN_1, REQUEST_DESTINATION_2, "car", "foot");

    private static final String DEFAULT_CAR_PROFILE_NAME = "car_default";
    private static final String DEFAULT_TRUCK_PROFILE_NAME = "truck_default";
    private static final String DEFAULT_SMALL_TRUCK_PROFILE_NAME = "small_truck_default";
    private static final String DEFAULT_BIKE_PROFILE_NAME = "bike_default";
    private static final String DEFAULT_FOOT_PROFILE_NAME = "foot_default";

    private static final RouterOuterClass.StreetRouteRequest AUTO_REQUEST =
            createStreetRequest("car", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest AUTO_REQUEST_WITH_ALTERNATIVES =
            createStreetRequest("car", true, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest WALK_REQUEST =
            createStreetRequest("foot", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest TRUCK_REQUEST =
            createStreetRequest(DEFAULT_TRUCK_PROFILE_NAME, false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest SMALL_TRUCK_REQUEST =
            createStreetRequest(DEFAULT_SMALL_TRUCK_PROFILE_NAME, false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);

    private static final RouterOuterClass.IsochroneRouteRequest STREET_ISOCHRONE_REQUEST_THREE_BUCKET =
            createStreetIsochroneRequest(REQUEST_ORIGIN_2, 3, 60 * 5, false);
    private static final RouterOuterClass.IsochroneRouteRequest STREET_ISOCHRONE_REQUEST_OUT_OF_BOUNDS =
            createStreetIsochroneRequest(new double[]{37.0, -121.43714698730038}, 3, 60 * 5, false);
    private static final RouterOuterClass.IsochroneRouteRequest STREET_ISOCHRONE_REQUEST_FIVE_BUCKET =
            createStreetIsochroneRequest(REQUEST_ORIGIN_2, 5, 60 * 5, false);
    private static final RouterOuterClass.IsochroneRouteRequest STREET_ISOCHRONE_REQUEST_TEN_MIN =
            createStreetIsochroneRequest(REQUEST_ORIGIN_2, 3, 60 * 10, false);
    private static final RouterOuterClass.IsochroneRouteRequest STREET_ISOCHRONE_REQUEST_REVERSE_FLOW =
            createStreetIsochroneRequest(REQUEST_ORIGIN_2, 3, 60 * 5, true);
    private static final RouterOuterClass.PtIsochroneRouteRequest PT_ISOCHRONE_REQUEST_THREE_BUCKET =
            createPtIsochroneRequest(REQUEST_DESTINATION_1, 3, 60 * 10, false);
    private static final RouterOuterClass.PtIsochroneRouteRequest PT_ISOCHRONE_REQUEST_OUT_OF_BOUNDS =
            createPtIsochroneRequest(new double[]{37.0, -121.43714698730038}, 3, 60 * 10, false);
    private static final RouterOuterClass.PtIsochroneRouteRequest PT_ISOCHRONE_REQUEST_FIVE_BUCKET =
            createPtIsochroneRequest(REQUEST_DESTINATION_1, 5, 60 * 10, false);
    private static final RouterOuterClass.PtIsochroneRouteRequest PT_ISOCHRONE_REQUEST_FIFTEEN_MIN =
            createPtIsochroneRequest(REQUEST_DESTINATION_1, 3, 60 * 15, false);
    private static final RouterOuterClass.PtIsochroneRouteRequest PT_ISOCHRONE_REQUEST_REVERSE_FLOW =
            createPtIsochroneRequest(REQUEST_DESTINATION_1, 3, 60 * 10, true);

    private static final long THURTON_DRIVE_OSM_ID = 10485465;
    // n.b. graphhopper internally rounds car speeds to the nearest multiple of 5 and truck speeds to the nearest multiple
    // of 2 (see speed_factor property in VehicleEncodedValues#car and TruckFlagEncoder.TRUCK_SPEED_FACTOR), so we choose
    // a custom speed that's a multiple of both to allow for straightforward equality comparisons
    private static final double THURTON_DRIVE_CUSTOM_SPEED = 90;
    private static final String CUSTOM_THURTON_DRIVE_CAR_PROFILE_NAME = "car_custom_fast_thurton_drive";
    private static final String CLOSED_BASELINE_ROAD_CAR_PROFILE_NAME = "car_custom_closed_baseline_road";
    private static final String CLOSED_BASELINE_ROAD_BIKE_PROFILE_NAME = "bike_custom_closed_baseline_road";
    private static final String CLOSED_BASELINE_ROAD_FOOT_PROFILE_NAME = "foot_custom_closed_baseline_road";
    private static final ImmutableSet<String> CAR_PROFILES =
            ImmutableSet.of("car", "car_freeway", DEFAULT_CAR_PROFILE_NAME, CUSTOM_THURTON_DRIVE_CAR_PROFILE_NAME, CLOSED_BASELINE_ROAD_CAR_PROFILE_NAME);
    private static final ImmutableSet<String> WALK_PROFILES = ImmutableSet.of("foot", DEFAULT_FOOT_PROFILE_NAME, CLOSED_BASELINE_ROAD_FOOT_PROFILE_NAME);

    private static final ImmutableMap<String, String> CUSTOM_THURTON_DRIVE_PROFILE_TO_DEFAULT_PROFILE = ImmutableMap.of(CUSTOM_THURTON_DRIVE_CAR_PROFILE_NAME, DEFAULT_CAR_PROFILE_NAME, "truck_custom_fast_thurton_drive", DEFAULT_TRUCK_PROFILE_NAME, "small_truck_custom_fast_thurton_drive", DEFAULT_SMALL_TRUCK_PROFILE_NAME);

    private static router.RouterGrpc.RouterBlockingStub routerStub = null;
    private static WKTReader wktReader = new WKTReader();

    @BeforeAll
    public static void startTestServer() throws Exception {
        // Grab instances of auto/bike/ped router and PT router
        GraphHopper graphHopper = graphHopperManaged.getGraphHopper();
        PtRouter ptRouter = null;
        if (graphHopper instanceof GraphHopperGtfs) {
            ptRouter = new PtRouterTripBasedImpl(graphHopper, graphHopperConfiguration,
                    graphHopper.getTranslationMap(), graphHopper.getBaseGraph(),
                    graphHopper.getEncodingManager(), graphHopper.getLocationIndex(),
                    ((GraphHopperGtfs) graphHopper).getGtfsStorage(),
                    graphHopper.getPathDetailsBuilderFactory());
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
        }

        // Start in-process test server + instantiate stub
        String uniqueName = InProcessServerBuilder.generateName();
        InProcessServerBuilder.forName(uniqueName)
                .directExecutor() // directExecutor is fine for unit tests
                .addService(new RouterImpl(graphHopper, ptRouter, gtfsLinkMappings,
                        gtfsRouteInfo, gtfsFeedIdMapping, null, TEST_REGION_NAME, TEST_RELEASE_NAME))
                .addService(ProtoReflectionService.newInstance())
                .build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(uniqueName)
                .directExecutor()
                .build();

        routerStub = router.RouterGrpc.newBlockingStub(channel);
    }

    private static RouterOuterClass.StreetRouteRequest createStreetRequest(String mode, boolean alternatives,
                                                                           double[] from, double[] to) {
        return createStreetRequest(mode, alternatives, from, to, true);
    }

    private static RouterOuterClass.StreetRouteRequest createStreetRequest(String mode, boolean alternatives,
                                                                           double[] from, double[] to, boolean returnFullPathDetails) {
        return RouterOuterClass.StreetRouteRequest.newBuilder()
                .addPoints(0, RouterOuterClass.Point.newBuilder()
                        .setLat(from[0])
                        .setLon(from[1])
                        .build())
                .addPoints(1, RouterOuterClass.Point.newBuilder()
                        .setLat(to[0])
                        .setLon(to[1])
                        .build())
                .setProfile(mode)
                .setAlternateRouteMaxPaths(alternatives ? 5 : 0)
                // below factors allow for long or very similar alternate routes for the sake of testing
                .setAlternateRouteMaxWeightFactor(3.0)
                .setAlternateRouteMaxShareFactor(0.9)
                .setReturnFullPathDetails(returnFullPathDetails)
                .build();
    }

    private static RouterOuterClass.PtRouteRequest createPtRequest(double[] from, double[] to) {
        // Mimics the python client, which sends empty strings when access/egress modes are specified
        return createPtRequest(from, to, "", "");
    }

    private static RouterOuterClass.PtRouteRequest createPtRequest(double[] from, double[] to, String accessMode, String egressMode) {
        return RouterOuterClass.PtRouteRequest.newBuilder()
                .addPoints(0, RouterOuterClass.Point.newBuilder()
                        .setLat(from[0])
                        .setLon(from[1])
                        .build())
                .addPoints(1, RouterOuterClass.Point.newBuilder()
                        .setLat(to[0])
                        .setLon(to[1])
                        .build())
                .setEarliestDepartureTime(EARLIEST_DEPARTURE_TIME)
                .setLimitSolutions(4)
                .setMaxProfileDuration(10)
                .setLimitStreetTimeSeconds(1440)
                .setUsePareto(false)
                .setBetaTransfers(1440000)
                .setMaxVisitedNodes(1000000)
                .setAccessMode(accessMode)
                .setEgressMode(egressMode)
                .setBetaAccessTime(1.5)
                .setBetaEgressTime(1.5)
                .build();
    }

    private static RouterOuterClass.IsochroneRouteRequest createStreetIsochroneRequest(double[] center, int nBuckets, int timeLimit, boolean reverseFlow) {
        return RouterOuterClass.IsochroneRouteRequest.newBuilder()
                .setCenter(RouterOuterClass.Point.newBuilder()
                        .setLat(center[0])
                        .setLon(center[1])
                        .build())
                .setMode("car")
                .setNBuckets(nBuckets)
                .setReverseFlow(reverseFlow)
                .setTimeLimit(timeLimit)
                .setTolerance(0)
                .setFullGeometry(true)
                .build();
    }

    private static RouterOuterClass.PtIsochroneRouteRequest createPtIsochroneRequest(double[] center, int nBuckets, int timeLimit, boolean reverseFlow) {
        return RouterOuterClass.PtIsochroneRouteRequest.newBuilder()
                .setCenter(RouterOuterClass.Point.newBuilder()
                        .setLat(center[0])
                        .setLon(center[1])
                        .build())
                .setNBuckets(nBuckets)
                .setReverseFlow(reverseFlow)
                .setTimeLimit(timeLimit)
                .setEarliestDepartureTime(EARLIEST_DEPARTURE_TIME)
                .setBlockedRouteTypes(0)
                .build();
    }

    @Test
    public void testInterFeedPublicTransitQuery() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_DIFF_FEEDS);

        Map<String, Integer> expectedModeCounts = Maps.newHashMap();
        expectedModeCounts.put("car", 0);
        expectedModeCounts.put("foot", 3);

        checkTransitQuery(response, 2, 3,
                Lists.newArrayList("ACCESS", "TRANSFER", "EGRESS"),
                expectedModeCounts,
                Lists.newArrayList(34, 142, 1, 194, 15)
        );
    }

    @Test
    public void testIntraFeedPublicTransitQuery() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_SAME_FEED);

        Map<String, Integer> expectedModeCounts = Maps.newHashMap();
        expectedModeCounts.put("car", 0);
        expectedModeCounts.put("foot", 3);

        checkTransitQuery(response, 2, 3,
                Lists.newArrayList("ACCESS", "TRANSFER", "EGRESS"),
                expectedModeCounts,
                Lists.newArrayList(5, 28, 1, 202, 15)
        );
    }

    @Test
    public void testAccessEgressCustomModes() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_PARK_N_RIDE);

        Map<String, Integer> expectedModeCounts = Maps.newHashMap();
        expectedModeCounts.put("car", 1);
        expectedModeCounts.put("foot", 1);

        checkTransitQuery(response, 1, 2,
                Lists.newArrayList("ACCESS", "EGRESS"),
                expectedModeCounts,
                Lists.newArrayList(118, 69, 27)
        );
    }

    @Test
    public void testAccessEgressCustomModesWithTransfer() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_PARK_N_RIDE_W_TRANSFER);

        Map<String, Integer> expectedModeCounts = Maps.newHashMap();
        expectedModeCounts.put("car", 1);
        expectedModeCounts.put("foot", 2);

        // Expected route is [car access -> transit -> transfer -> transit -> walk egress]
        checkTransitQuery(response, 2, 3,
                Lists.newArrayList("ACCESS", "TRANSFER", "EGRESS"),
                expectedModeCounts,
                Lists.newArrayList(26, 61, 5, 69, 3)
        );
    }

    private void checkTransitQuery(RouterOuterClass.PtRouteReply response,
                                   int expectedPtLegs, int expectedStreetLegs,
                                   List<String> expectedTravelSegmentTypes,
                                   Map<String, Integer> expectedModeCounts,
                                   List<Integer> expectedStableEdgeIdCount) {
        // Check details of Path are set correctly
        assertTrue(response.getPathsList().size() >= 1);
        RouterOuterClass.PtPath path = response.getPaths(0);
        List<RouterOuterClass.PtLeg> streetLegs = path.getLegsList().stream()
                .filter(l -> !l.hasTransitMetadata()).collect(Collectors.toList());
        List<RouterOuterClass.PtLeg> ptLegs = path.getLegsList().stream()
                .filter(RouterOuterClass.PtLeg::hasTransitMetadata).collect(Collectors.toList());
        assertEquals(expectedPtLegs, ptLegs.size());
        assertEquals(expectedStreetLegs, streetLegs.size());
        assertTrue(path.getDistanceMeters() > 0);
        assertTrue(path.getDurationMillis() > 0);

        // Check that street legs contain proper info
        List<String> observedTravelSegmentTypes = Lists.newArrayList();
        Map<String, Integer> observedModeCounts = Maps.newHashMap();
        observedModeCounts.put("car", 0);
        observedModeCounts.put("foot", 0);
        List<String> observedStableEdgeIds = Lists.newArrayList();
        int observedStableEdgeIdCount = 0;
        double observedDistanceMeters = 0;
        for (RouterOuterClass.PtLeg streetLeg : streetLegs) {
            assertTrue(streetLeg.getStableEdgeIdsCount() > 0);
            observedStableEdgeIdCount += streetLeg.getStableEdgeIdsCount();
            observedStableEdgeIds.addAll(streetLeg.getStableEdgeIdsList());
            assertTrue(streetLeg.getArrivalTime().getSeconds() > streetLeg.getDepartureTime().getSeconds());
            assertTrue(streetLeg.getDistanceMeters() > 0);
            assertFalse(streetLeg.getTravelSegmentType().isEmpty());
            observedTravelSegmentTypes.add(streetLeg.getTravelSegmentType());
            observedModeCounts.put(streetLeg.getMode(), observedModeCounts.get(streetLeg.getMode()) + 1);
            observedDistanceMeters += streetLeg.getDistanceMeters();
        }
        assertEquals(expectedTravelSegmentTypes, observedTravelSegmentTypes);
        assertEquals(expectedModeCounts, observedModeCounts);

        // Check that PT legs contains proper info
        for (RouterOuterClass.PtLeg ptLeg : ptLegs) {
            assertTrue(ptLeg.getArrivalTime().getSeconds() > ptLeg.getDepartureTime().getSeconds());
            assertTrue(ptLeg.getStableEdgeIdsCount() > 0); // check that the GTFS link mapper worked
            observedStableEdgeIdCount += ptLeg.getStableEdgeIdsCount();
            observedStableEdgeIds.addAll(ptLeg.getStableEdgeIdsList());
            assertTrue(ptLeg.getDistanceMeters() > 0);
            observedDistanceMeters += ptLeg.getDistanceMeters();

            RouterOuterClass.TransitMetadata ptMetadata = ptLeg.getTransitMetadata();
            assertFalse(ptMetadata.getTripId().isEmpty());
            assertFalse(ptMetadata.getRouteId().isEmpty());
            assertFalse(ptMetadata.getAgencyName().isEmpty());
            assertFalse(ptMetadata.getRouteShortName().isEmpty());
            assertFalse(ptMetadata.getRouteLongName().isEmpty());
            assertFalse(ptMetadata.getRouteType().isEmpty());
            assertFalse(ptMetadata.getDirection().isEmpty());
        }
        assertEquals(observedStableEdgeIdCount, observedStableEdgeIds.size());
        assertEquals(path.getDistanceMeters(), observedDistanceMeters, 0.0001);

        // Check stops in first PT leg
        RouterOuterClass.PtLeg firstLeg = ptLegs.get(0);
        assertTrue(firstLeg.getTransitMetadata().getStopsList().size() > 0);
        for (RouterOuterClass.Stop stop : firstLeg.getTransitMetadata().getStopsList()) {
            assertFalse(stop.getStopId().isEmpty());
            assertEquals(1, TEST_GTFS_FILE_NAMES.stream().filter(f -> stop.getStopId().startsWith(f)).count());
            assertFalse(stop.getStopName().isEmpty());
            assertTrue(stop.hasPoint());
        }

        // Check number of stable edge IDs for each leg is as-expected
        for (int i = 0; i < path.getLegsList().size(); i++) {
            assertEquals(expectedStableEdgeIdCount.get(i), path.getLegsList().get(i).getStableEdgeIdsCount());
        }
    }

    @Test
    public void testAutoQuery() {
        final RouterOuterClass.StreetRouteReply response = routerStub.routeStreetMode(AUTO_REQUEST);

        // even without alternatives, we expect 4 auto paths, because we route with 4 auto profiles + combine results
        Predicate<Long> perProfilePathCountPredicate = pathCount -> pathCount == 1L;
        checkStreetBasedResponse(response, CAR_PROFILES, perProfilePathCountPredicate);
    }

    @Test
    public void testTruckQueries() {
        Predicate<Long> perProfilePathCountPredicate = pathCount -> pathCount == 1L;

        final RouterOuterClass.StreetRouteReply truckResponse = routerStub.routeStreetMode(TRUCK_REQUEST);
        Set<String> expectedTruckProfiles = ImmutableSet.of(DEFAULT_TRUCK_PROFILE_NAME);
        checkStreetBasedResponse(truckResponse, expectedTruckProfiles, perProfilePathCountPredicate);

        final RouterOuterClass.StreetRouteReply smallTruckResponse = routerStub.routeStreetMode(SMALL_TRUCK_REQUEST);
        expectedTruckProfiles = ImmutableSet.of(DEFAULT_SMALL_TRUCK_PROFILE_NAME);
        checkStreetBasedResponse(smallTruckResponse, expectedTruckProfiles, perProfilePathCountPredicate);

        // Check truck and small_truck return slightly different results
        long totalTruckEdgeDuration = truckResponse
                .getPathsList().stream()
                .mapToLong(RouterOuterClass.StreetPath::getDurationMillis)
                .sum();
        long totalSmallTruckEdgeDuration = smallTruckResponse
                .getPathsList().stream()
                .mapToLong(RouterOuterClass.StreetPath::getDurationMillis)
                .sum();
        assertNotEquals(totalTruckEdgeDuration, totalSmallTruckEdgeDuration);
    }

    @Test
    public void testWalkQuery() {
        final RouterOuterClass.StreetRouteReply response = routerStub.routeStreetMode(WALK_REQUEST);
        Predicate<Long> perProfilePathCountPredicate = pathCount -> pathCount == 1L;
        checkStreetBasedResponse(response, WALK_PROFILES, perProfilePathCountPredicate);
    }

    @Test
    public void testAutoQueryWithAlternatives() {
        final RouterOuterClass.StreetRouteReply response = routerStub.routeStreetMode(AUTO_REQUEST_WITH_ALTERNATIVES);

        // we route with 4 auto profiles and combine results, and each profile should have produced at least one
        // alternate
        Predicate<Long> perProfilePathCountPredicate = pathCount -> pathCount > 1L;
        checkStreetBasedResponse(response, CAR_PROFILES, perProfilePathCountPredicate);
    }

    private static void checkStreetBasedResponse(RouterOuterClass.StreetRouteReply response,
                                                 Set<String> expectedProfiles,
                                                 Predicate<Long> perProfilePathCountPredicate) {
        checkStreetBasedResponseProfiles(response, expectedProfiles, perProfilePathCountPredicate);
        for (RouterOuterClass.StreetPath path : response.getPathsList()) {
            assertTrue(path.getDurationMillis() > 0);
            assertTrue(path.getDistanceMeters() > 0);
            assertTrue(path.getStableEdgeIdsCount() > 0);
            assertEquals(path.getStableEdgeIdsCount(), path.getEdgeDurationsMillisCount());
            int totalDurationMillis = path.getEdgeDurationsMillisList().stream().mapToInt(Long::intValue).sum();
            assertEquals(path.getDurationMillis(), totalDurationMillis);

            Map<String, List<RouterOuterClass.StreetPathDetailValue>> pathDetailsByName = getPathDetailsByName(path);


            // when the full path details are returned, stable edge ids and edge duration millis should also be present
            // there (with higher fidelity, since the path details contain the mapping to GH edges). the top-level and
            // path details values should match
            if (!pathDetailsByName.isEmpty()) {
                assertTrue(pathDetailsByName.containsKey(ReplicaPathDetails.SPEED));
                assertTrue(pathDetailsByName.containsKey(ReplicaPathDetails.OSM_ID));
                List<RouterOuterClass.StreetPathDetailValue> stableEdgeIdPathDetails = pathDetailsByName.get(ReplicaPathDetails.STABLE_EDGE_IDS);
                List<RouterOuterClass.StreetPathDetailValue> timePathDetails = pathDetailsByName.get(ReplicaPathDetails.TIME);

                // the time and stable edge id details should align with the top-level fields and with each other
                assertEquals(stableEdgeIdPathDetails.size(), timePathDetails.size());
                assertEquals(stableEdgeIdPathDetails.size(), path.getStableEdgeIdsCount());
                assertEquals(timePathDetails.size(), path.getEdgeDurationsMillisCount());

                for (int i = 0; i < stableEdgeIdPathDetails.size(); i++) {
                    RouterOuterClass.StreetPathDetailValue stableEdgeIdPathDetail = stableEdgeIdPathDetails.get(i);
                    RouterOuterClass.StreetPathDetailValue timePathDetail = timePathDetails.get(i);

                    assertEquals(stableEdgeIdPathDetail.getValue(), path.getStableEdgeIds(i));
                    assertEquals(Long.parseLong(timePathDetail.getValue()), path.getEdgeDurationsMillis(i));

                    assertEquals(stableEdgeIdPathDetail.getGhEdgeStartIndex(), timePathDetail.getGhEdgeStartIndex());
                    assertEquals(stableEdgeIdPathDetail.getGhEdgeEndIndex(), timePathDetail.getGhEdgeEndIndex());
                }
            }
        }
    }

    private static Map<String, List<RouterOuterClass.StreetPathDetailValue>> getPathDetailsByName(RouterOuterClass.StreetPath path) {
        return path.getPathDetailsList().stream()
                .collect(Collectors.toMap(RouterOuterClass.StreetPathDetail::getDetail,
                        RouterOuterClass.StreetPathDetail::getValuesList));
    }

    private static void checkStreetBasedResponseProfiles(RouterOuterClass.StreetRouteReply response,
                                                         Set<String> expectedProfiles,
                                                         Predicate<Long> perProfilePathCountPredicate) {
        Map<String, Long> profileToPathCount = response.getPathsList().stream()
                .collect(Collectors.groupingBy(RouterOuterClass.StreetPath::getProfile, Collectors.counting()));

        assertEquals(expectedProfiles, profileToPathCount.keySet());
        for (String profile : profileToPathCount.keySet()) {
            assertTrue(perProfilePathCountPredicate.test(profileToPathCount.get(profile)),
                    "Street path with profile " + profile + " failed path count predicate");
        }
    }

    @Test
    public void testAutoFasterThanWalk() {
        final RouterOuterClass.StreetRouteReply autoResponse = routerStub.routeStreetMode(AUTO_REQUEST);
        final RouterOuterClass.StreetRouteReply walkResponse = routerStub.routeStreetMode(WALK_REQUEST);
        assertTrue(autoResponse.getPaths(0).getDurationMillis() <
                walkResponse.getPaths(0).getDurationMillis());
    }

    @Test
    public void testBadPointsStreetMode() {
        RouterOuterClass.StreetRouteRequest badAutoRequest = AUTO_REQUEST.toBuilder()
                .setPoints(0, RouterOuterClass.Point.newBuilder().setLat(38.0).setLon(-94.0).build()).build();
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> {routerStub.routeStreetMode(badAutoRequest);});
        assertSame(exception.getStatus().getCode(), Status.NOT_FOUND.getCode());
    }

    @Test
    public void testBadPointsTransit() {
        RouterOuterClass.PtRouteRequest badPtRequest = PT_REQUEST_DIFF_FEEDS.toBuilder()
                .setPoints(0, RouterOuterClass.Point.newBuilder().setLat(38.0).setLon(-94.0).build()).build();
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> {routerStub.routePt(badPtRequest);});
        assertSame(exception.getStatus().getCode(), Status.NOT_FOUND.getCode());
    }

    @Test
    public void testBadTimeTransit() {
        RouterOuterClass.PtRouteRequest badPtRequest = PT_REQUEST_DIFF_FEEDS.toBuilder()
                .setEarliestDepartureTime(Timestamp.newBuilder().setSeconds(100).build()).build();
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> {routerStub.routePt(badPtRequest);});
        assertSame(exception.getStatus().getCode(), Status.NOT_FOUND.getCode());
    }

    @Test
    public void testAutoQueryCustomSpeeds() {
        // two nearby points in Roseville along Thurton Drive
        double[] origin = {38.75610459830836, -121.31971682573254};
        double[] dest = {38.75276653167277, -121.32034746128646};

        Predicate<Long> onlyOnePathPredicate = pathCount -> pathCount == 1L;

        for (Map.Entry<String, String> profileEntry : CUSTOM_THURTON_DRIVE_PROFILE_TO_DEFAULT_PROFILE.entrySet()) {
            String customProfile = profileEntry.getKey();
            String defaultProfile = profileEntry.getValue();

            final RouterOuterClass.StreetRouteReply customSpeedsResponse = routerStub.routeStreetMode(
                    createStreetRequest(customProfile, false, origin, dest));
            checkStreetBasedResponse(customSpeedsResponse, ImmutableSet.of(customProfile), onlyOnePathPredicate);

            final RouterOuterClass.StreetRouteReply defaultSpeedsResponse = routerStub.routeStreetMode(
                    createStreetRequest(defaultProfile, false, origin, dest));
            checkStreetBasedResponse(defaultSpeedsResponse, ImmutableSet.of(defaultProfile), onlyOnePathPredicate);

            RouterOuterClass.StreetPath customSpeedsPath = Iterables.getOnlyElement(customSpeedsResponse.getPathsList());
            RouterOuterClass.StreetPath defaultSpeedsPath = Iterables.getOnlyElement(defaultSpeedsResponse.getPathsList());

            double customSpeed = Double.parseDouble(getOnlyPathDetailValue(ReplicaPathDetails.SPEED, customSpeedsPath));
            assertEquals(THURTON_DRIVE_CUSTOM_SPEED, customSpeed);

            double defaultSpeed = Double.parseDouble(getOnlyPathDetailValue(ReplicaPathDetails.SPEED, defaultSpeedsPath));
            // the custom speeds profile sets the Thurton Drive speed very high, so the travel time using this profile
            // should be less than the default
            assertTrue(defaultSpeed < customSpeed);
            assertTrue(customSpeedsPath.getDurationMillis() < defaultSpeedsPath.getDurationMillis());

            // both paths should only involve the Thurton drive OSM way
            for (RouterOuterClass.StreetPath path : Arrays.asList(customSpeedsPath, defaultSpeedsPath)) {
                int osmId = Integer.parseInt(getOnlyPathDetailValue(ReplicaPathDetails.OSM_ID, path));
                assertEquals(THURTON_DRIVE_OSM_ID, osmId);
            }
        }
    }

    // requires streetPath to only have one value for the given detailName
    private static String getOnlyPathDetailValue(String detailName, RouterOuterClass.StreetPath streetPath) {
        Map<String, List<RouterOuterClass.StreetPathDetailValue>> customPathDetailsByName = getPathDetailsByName(streetPath);
        return Iterables.getOnlyElement(customPathDetailsByName.get(detailName)).getValue();
    }

    // tests road closure simulation via setting a custom speed for an OSM way to 0
    @Test
    public void testAutoQueryZeroCustomSpeeds() {
        // two nearby points in Roseville along Baseline Road, before and after the portion with zero custom speed (OSM way ID 76254223)
        double[] origin = {38.75184544401385, -121.33725463050372};
        double[] dest = {38.75181929375432, -121.30902559426471};

        Predicate<Long> onlyOnePathPredicate = pathCount -> pathCount == 1L;

        checkCustomSpeedRoadClosure(CLOSED_BASELINE_ROAD_CAR_PROFILE_NAME, DEFAULT_CAR_PROFILE_NAME, origin, dest, onlyOnePathPredicate);
        checkCustomSpeedRoadClosure(CLOSED_BASELINE_ROAD_BIKE_PROFILE_NAME, DEFAULT_BIKE_PROFILE_NAME, origin, dest, onlyOnePathPredicate);
        checkCustomSpeedRoadClosure(CLOSED_BASELINE_ROAD_FOOT_PROFILE_NAME, DEFAULT_FOOT_PROFILE_NAME, origin, dest, onlyOnePathPredicate);
    }

    private static void checkCustomSpeedRoadClosure(String closedRoadProfileName, String defaultProfileName,
                                                    double[] origin, double[] dest, Predicate<Long> onlyOnePathPredicate) {
        final RouterOuterClass.StreetRouteReply customSpeedsResponse = routerStub.routeStreetMode(
                createStreetRequest(closedRoadProfileName, false, origin, dest));
        checkStreetBasedResponse(customSpeedsResponse, ImmutableSet.of(closedRoadProfileName), onlyOnePathPredicate);

        final RouterOuterClass.StreetRouteReply defaultSpeedsResponse = routerStub.routeStreetMode(
                createStreetRequest(defaultProfileName, false, origin, dest));
        checkStreetBasedResponse(defaultSpeedsResponse, ImmutableSet.of(defaultProfileName), onlyOnePathPredicate);

        RouterOuterClass.StreetPath customSpeedsPath = Iterables.getOnlyElement(customSpeedsResponse.getPathsList());
        RouterOuterClass.StreetPath defaultSpeedsPath = Iterables.getOnlyElement(defaultSpeedsResponse.getPathsList());

        // the road closure profile should take a roundabout route due to the closure, so its distance and travel time
        // should be greater than the default
        assertTrue(customSpeedsPath.getDistanceMeters() > defaultSpeedsPath.getDistanceMeters());
        assertTrue(customSpeedsPath.getDurationMillis() > defaultSpeedsPath.getDurationMillis());
    }

    @Test
    public void testPathDetailsReturnedOnlyWhenRequested() {
        final RouterOuterClass.StreetRouteReply responseWithoutDetails = routerStub.routeStreetMode(createStreetRequest("car", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1, false));
        assertTrue(responseWithoutDetails.getPathsList().stream().allMatch(path -> path.getPathDetailsCount() == 0));

        final RouterOuterClass.StreetRouteReply responseWithDetails = routerStub.routeStreetMode(createStreetRequest("car", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1, true));
        assertTrue(responseWithDetails.getPathsList().stream()
                .map(RouterServerTest::getPathDetailsByName)
                .map(Map::keySet)
                .allMatch(details -> details.containsAll(List.of(
                        ReplicaPathDetails.STABLE_EDGE_IDS, ReplicaPathDetails.TIME, ReplicaPathDetails.OSM_ID, ReplicaPathDetails.SPEED))));

        // top-level stable edge id and time fields should always be returned
        for (RouterOuterClass.StreetRouteReply streetRouteReply : List.of(responseWithDetails, responseWithDetails)) {
            assertTrue(streetRouteReply.getPathsList().stream().allMatch(path -> path.getStableEdgeIdsCount() > 0));
            assertTrue(streetRouteReply.getPathsList().stream().allMatch(path -> path.getEdgeDurationsMillisCount() > 0));
        }
    }

    @Test
    public void testStreetIsochrone() throws ParseException {
        final RouterOuterClass.IsochroneRouteReply threeBucketResponse = routerStub.routeIsochrone(STREET_ISOCHRONE_REQUEST_THREE_BUCKET);
        final RouterOuterClass.IsochroneRouteReply fiveBucketResponse = routerStub.routeIsochrone(STREET_ISOCHRONE_REQUEST_FIVE_BUCKET);
        final RouterOuterClass.IsochroneRouteReply tenMinResponse = routerStub.routeIsochrone(STREET_ISOCHRONE_REQUEST_TEN_MIN);
        final RouterOuterClass.IsochroneRouteReply reverseFlowResponse = routerStub.routeIsochrone(STREET_ISOCHRONE_REQUEST_REVERSE_FLOW);

        // Correct number of buckets returned
        assertEquals(3, threeBucketResponse.getBucketsList().size());
        assertEquals(3, tenMinResponse.getBucketsList().size());
        assertEquals(5, fiveBucketResponse.getBucketsList().size());

        // Bucket indices are correct
        List<Integer> returnedBuckets = fiveBucketResponse.getBucketsList().stream()
                .map(RouterOuterClass.IsochroneBucket::getBucket)
                .collect(Collectors.toList());
        assertEquals(List.of(0, 1, 2, 3, 4), returnedBuckets);

        // Three-bucket/5 min response has larger buckets than five-bucket/5 min response
        MultiPolygon threeBucketInnerBucket = (MultiPolygon) wktReader.read(threeBucketResponse.getBuckets(0).getGeometry());
        MultiPolygon fiveBucketInnerBucket = (MultiPolygon) wktReader.read(fiveBucketResponse.getBuckets(0).getGeometry());
        assertTrue(threeBucketInnerBucket.getArea() > fiveBucketInnerBucket.getArea());

        // 5 min/3 bucket response has smaller buckets than 10 min/3 bucket response
        MultiPolygon fiveMinInnerBucket = (MultiPolygon) wktReader.read(threeBucketResponse.getBuckets(0).getGeometry());
        MultiPolygon tenMinInnerBucket = (MultiPolygon) wktReader.read(tenMinResponse.getBuckets(0).getGeometry());
        assertTrue(fiveMinInnerBucket.getArea() < tenMinInnerBucket.getArea());

        // With reverse flow set, buckets are different
        MultiPolygon reverseFlowInnerBucket = (MultiPolygon) wktReader.read(reverseFlowResponse.getBuckets(0).getGeometry());
        assertNotEquals(reverseFlowInnerBucket.getArea(), threeBucketInnerBucket.getArea());

        assertThrows(RuntimeException.class, () -> {
            routerStub.routeIsochrone(STREET_ISOCHRONE_REQUEST_OUT_OF_BOUNDS);
        });
    }

    @Test
    public void testPtIsochrone() throws ParseException {
        final RouterOuterClass.IsochroneRouteReply threeBucketResponse = routerStub.routePtIsochrone(PT_ISOCHRONE_REQUEST_THREE_BUCKET);
        final RouterOuterClass.IsochroneRouteReply fiveBucketResponse = routerStub.routePtIsochrone(PT_ISOCHRONE_REQUEST_FIVE_BUCKET);
        final RouterOuterClass.IsochroneRouteReply fifteenMinResponse = routerStub.routePtIsochrone(PT_ISOCHRONE_REQUEST_FIFTEEN_MIN);

        // Correct number of buckets returned
        assertEquals(3, threeBucketResponse.getBucketsList().size());
        assertEquals(3, fifteenMinResponse.getBucketsList().size());
        assertEquals(5, fiveBucketResponse.getBucketsList().size());

        // Bucket indices are correct
        List<Integer> returnedBuckets = fiveBucketResponse.getBucketsList().stream()
                .map(RouterOuterClass.IsochroneBucket::getBucket)
                .collect(Collectors.toList());
        assertEquals(List.of(0, 1, 2, 3, 4), returnedBuckets);

        // Three-bucket/10 min response has larger buckets than five-bucket/10 min response
        MultiPolygon threeBucketInnerBucket = (MultiPolygon) wktReader.read(threeBucketResponse.getBuckets(0).getGeometry());
        MultiPolygon fiveBucketInnerBucket = (MultiPolygon) wktReader.read(fiveBucketResponse.getBuckets(0).getGeometry());
        assertTrue(threeBucketInnerBucket.getArea() > fiveBucketInnerBucket.getArea());

        // 10 min/3 bucket response has smaller buckets than 15 min/3 bucket response
        MultiPolygon fiveMinInnerBucket = (MultiPolygon) wktReader.read(threeBucketResponse.getBuckets(0).getGeometry());
        MultiPolygon fifteenMinInnerBucket = (MultiPolygon) wktReader.read(fifteenMinResponse.getBuckets(0).getGeometry());
        assertTrue(fiveMinInnerBucket.getArea() < fifteenMinInnerBucket.getArea());

        assertThrows(RuntimeException.class, () -> {
            routerStub.routePtIsochrone(PT_ISOCHRONE_REQUEST_OUT_OF_BOUNDS);
        });

        // TODO: re-enable once reverseFlow works properly for PT isochrones
        /*
        final RouterOuterClass.IsochroneRouteReply reverseFlowResponse = routerStub.routePtIsochrone(PT_ISOCHRONE_REQUEST_REVERSE_FLOW);

        // With reverse flow set, buckets are different
        MultiPolygon reverseFlowInnerBucket = (MultiPolygon) wktReader.read(reverseFlowResponse.getBuckets(0).getGeometry());
        assertNotEquals(reverseFlowInnerBucket.getArea(), threeBucketInnerBucket.getArea());
        */
    }
}

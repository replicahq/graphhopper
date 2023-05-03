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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.Timestamp;
import com.graphhopper.GraphHopper;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.PtRouterImpl;
import com.graphhopper.gtfs.RealtimeFeed;
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
import org.mapdb.DB;
import org.mapdb.DBMaker;
import router.RouterOuterClass;

import java.io.File;
import java.time.Instant;
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
    // Departure time + ODs are chosen for micro_nor_cal test area, with a validity start date of
    // 2019-10-13, and a bbox of -122.41229018000416,-120.49584285533076,37.75738096439945,39.52415953258036

    private static final Timestamp EARLIEST_DEPARTURE_TIME =
            Timestamp.newBuilder().setSeconds(Instant.parse("2019-10-13T13:30:00Z").toEpochMilli() / 1000).build();
    private static final double[] REQUEST_ORIGIN_1 = {38.74891667931467,-121.29023848101498}; // Roseville area
    private static final double[] REQUEST_ORIGIN_2 = {38.59337420024281,-121.48746937746185}; // Sacramento area
    private static final double[] REQUEST_ORIGIN_3 = {38.508810062393245,-121.5085223084316}; // South of Sacramento area
    private static final double[] REQUEST_DESTINATION_1 = {38.55518457319914,-121.43714698730038}; // Sacramento area
    private static final double[] REQUEST_DESTINATION_2 = {38.64478548196401,-121.34168802760543}; // North of Sacramento area

    // Should force a transfer between routes from 2 distinct feeds
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_DIFF_FEEDS = createPtRequest(REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    // Should force a transfer between routes from the same feed
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_SAME_FEED = createPtRequest(REQUEST_ORIGIN_2, REQUEST_DESTINATION_1);
    // Tests park-and-ride routing, with custom access/egress modes
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_PARK_N_RIDE = createPtRequest(REQUEST_ORIGIN_1, REQUEST_DESTINATION_1, "car", "foot");
    // Tests park-and-ride routing for a longer route (with a transfer)
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_PARK_N_RIDE_W_TRANSFER = createPtRequest(REQUEST_ORIGIN_3, REQUEST_DESTINATION_2, "car", "foot");

    private static final RouterOuterClass.StreetRouteRequest AUTO_REQUEST =
            createStreetRequest("car", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest AUTO_REQUEST_WITH_ALTERNATIVES =
            createStreetRequest("car", true, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest WALK_REQUEST =
            createStreetRequest("foot", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest TRUCK_REQUEST =
            createStreetRequest("truck", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);
    private static final RouterOuterClass.StreetRouteRequest SMALL_TRUCK_REQUEST =
            createStreetRequest("small_truck", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION_1);

    private static final String FAST_THURTON_DRIVE_CAR_PROFILE_NAME = "car_custom_fast_thurton_drive";
    private static final String DEFAULT_CAR_PROFILE_NAME = "car_default";
    private static final ImmutableSet<String> CAR_PROFILES =
            ImmutableSet.of("car", "car_freeway", DEFAULT_CAR_PROFILE_NAME, FAST_THURTON_DRIVE_CAR_PROFILE_NAME);

    private static router.RouterGrpc.RouterBlockingStub routerStub = null;

    @BeforeAll
    public static void startTestServer() throws Exception {
        // Grab instances of auto/bike/ped router and PT router
        GraphHopper graphHopper = graphHopperManaged.getGraphHopper();
        PtRouter ptRouter = null;
        if (graphHopper instanceof GraphHopperGtfs) {
            ptRouter = new PtRouterImpl(graphHopperConfiguration,
                    graphHopper.getTranslationMap(), graphHopper.getBaseGraph(),
                    graphHopper.getEncodingManager(), graphHopper.getLocationIndex(),
                    ((GraphHopperGtfs) graphHopper).getGtfsStorage(), RealtimeFeed.empty(),
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
                .setBetaWalkTime(1.5)
                .setLimitStreetTimeSeconds(1440)
                .setUsePareto(false)
                .setBetaTransfers(1440000)
                .setMaxVisitedNodes(1000000)
                .setAccessMode(accessMode)
                .setEgressMode(egressMode)
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
                Lists.newArrayList(8, 201, 3, 184, 15)
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
                Lists.newArrayList(19, 59, 3, 164, 15)
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
                Lists.newArrayList(92, 154, 27)
        );
    }

    @Test
    public void testAccessEgressCustomModesWithTransfer() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_PARK_N_RIDE_W_TRANSFER);

        Map<String, Integer> expectedModeCounts = Maps.newHashMap();
        expectedModeCounts.put("car", 1);
        expectedModeCounts.put("foot", 2);

        // Expected route is [car access -> transit -> transfer -> transit -> transit (no transfer) -> walk egress]
        checkTransitQuery(response, 3, 3,
                Lists.newArrayList("ACCESS", "TRANSFER", "EGRESS"),
                expectedModeCounts,
                Lists.newArrayList(60, 102, 4, 243, 83, 11)
        );
    }

    private void checkTransitQuery(RouterOuterClass.PtRouteReply response,
                                   int expectedPtLegs, int expectedStreetLegs,
                                   List<String> expectedTravelSegmentTypes,
                                   Map<String, Integer> expectedModeCounts,
                                   List<Integer> expectedStableEdgeIdCount) {
        // Check details of Path are set correctly
        assertEquals(1, response.getPathsList().size());
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
        Set<String> expectedTruckProfiles = ImmutableSet.of("truck");
        checkStreetBasedResponse(truckResponse, expectedTruckProfiles, perProfilePathCountPredicate);

        final RouterOuterClass.StreetRouteReply smallTruckResponse = routerStub.routeStreetMode(SMALL_TRUCK_REQUEST);
        expectedTruckProfiles = ImmutableSet.of("small_truck");
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

        Set<String> expectedWalkProfiles = ImmutableSet.of("foot");
        Predicate<Long> perProfilePathCountPredicate = pathCount -> pathCount == 1L;
        checkStreetBasedResponse(response, expectedWalkProfiles, perProfilePathCountPredicate);
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
        }
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
        // two nearby points in Roseville along Thurton Drive (OSM way ID 10485465)
        double[] origin = {38.75610459830836, -121.31971682573254};
        double[] dest = {38.75276653167277, -121.32034746128646};

        Predicate<Long> onlyOnePathPredicate = pathCount -> pathCount == 1L;

        final RouterOuterClass.StreetRouteReply customSpeedsResponse = routerStub.routeStreetMode(
                createStreetRequest(FAST_THURTON_DRIVE_CAR_PROFILE_NAME, false, origin, dest));
        checkStreetBasedResponse(customSpeedsResponse, ImmutableSet.of(FAST_THURTON_DRIVE_CAR_PROFILE_NAME), onlyOnePathPredicate);

        final RouterOuterClass.StreetRouteReply defaultSpeedsResponse = routerStub.routeStreetMode(
                createStreetRequest(DEFAULT_CAR_PROFILE_NAME, false, origin, dest));
        checkStreetBasedResponse(defaultSpeedsResponse, ImmutableSet.of(DEFAULT_CAR_PROFILE_NAME), onlyOnePathPredicate);

        RouterOuterClass.StreetPath customSpeedsPath = Iterables.getOnlyElement(customSpeedsResponse.getPathsList());
        RouterOuterClass.StreetPath defaultSpeedsPath = Iterables.getOnlyElement(defaultSpeedsResponse.getPathsList());

        // the custom speeds profile sets the Thurton Drive speed very high, so the travel time using this profile
        // should be less than the default
        assertTrue(customSpeedsPath.getDurationMillis() < defaultSpeedsPath.getDurationMillis());
    }
}

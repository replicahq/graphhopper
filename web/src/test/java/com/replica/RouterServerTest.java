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

import com.google.common.collect.Lists;
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
    private static final double[] REQUEST_DESTINATION = {38.55518457319914,-121.43714698730038}; // Sacramento area
    // PT_REQUEST_1 should force a transfer between routes from 2 distinct feeds
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_1 = createPtRequest(REQUEST_ORIGIN_1, REQUEST_DESTINATION);
    // PT_REQUEST_2 should force a transfer between routes from the same feed
    private static final RouterOuterClass.PtRouteRequest PT_REQUEST_2 = createPtRequest(REQUEST_ORIGIN_2, REQUEST_DESTINATION);
    private static final RouterOuterClass.StreetRouteRequest AUTO_REQUEST =
            createStreetRequest("car", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION);
    private static final RouterOuterClass.StreetRouteRequest AUTO_REQUEST_WITH_ALTERNATIVES =
            createStreetRequest("car", true,REQUEST_ORIGIN_1, REQUEST_DESTINATION);
    private static final RouterOuterClass.StreetRouteRequest WALK_REQUEST =
            createStreetRequest("foot", false, REQUEST_ORIGIN_1, REQUEST_DESTINATION);

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
                .setAlternateRouteMaxWeightFactor(2.0)
                .setAlternateRouteMaxShareFactor(0.4)
                .build();
    }

    private static RouterOuterClass.PtRouteRequest createPtRequest(double[] from, double[] to) {
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
                .build();
    }

    @Test
    public void testInterFeedPublicTransitQuery() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_1);

        // Check details of Path are set correctly
        assertEquals(1, response.getPathsList().size());
        RouterOuterClass.PtPath path = response.getPaths(0);
        List<RouterOuterClass.PtLeg> footLegs = path.getLegsList().stream()
                .filter(l -> !l.hasTransitMetadata()).collect(Collectors.toList());
        List<RouterOuterClass.PtLeg> ptLegs = path.getLegsList().stream()
                .filter(RouterOuterClass.PtLeg::hasTransitMetadata).collect(Collectors.toList());
        assertEquals(2, ptLegs.size());
        assertEquals(3, footLegs.size()); // access, transfer, and egress
        assertTrue(path.getDistanceMeters() > 0);
        assertTrue(path.getDurationMillis() > 0);

        // Check that foot legs contain proper info
        List<String> observedTravelSegmentTypes = Lists.newArrayList();
        List<String> expectedTravelSegmentTypes = Lists.newArrayList("ACCESS", "TRANSFER", "EGRESS");
        List<String> observedStableEdgeIds = Lists.newArrayList();
        int observedStableEdgeIdCount = 0;
        double observedDistanceMeters = 0;
        for (RouterOuterClass.PtLeg footLeg : footLegs) {
            assertTrue(footLeg.getStableEdgeIdsCount() > 0);
            observedStableEdgeIdCount += footLeg.getStableEdgeIdsCount();
            observedStableEdgeIds.addAll(footLeg.getStableEdgeIdsList());
            assertTrue(footLeg.getArrivalTime().getSeconds() > footLeg.getDepartureTime().getSeconds());
            assertTrue(footLeg.getDistanceMeters() > 0);
            assertFalse(footLeg.getTravelSegmentType().isEmpty());
            observedTravelSegmentTypes.add(footLeg.getTravelSegmentType());
            observedDistanceMeters += footLeg.getDistanceMeters();
        }
        assertEquals(expectedTravelSegmentTypes, observedTravelSegmentTypes);

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
    }

    @Test
    public void testIntraFeedPublicTransitQuery() {
        final RouterOuterClass.PtRouteReply response = routerStub.routePt(PT_REQUEST_2);

        // Check details of Path are set correctly
        assertEquals(1, response.getPathsList().size());
        RouterOuterClass.PtPath path = response.getPaths(0);
        List<RouterOuterClass.PtLeg> footLegs = path.getLegsList().stream()
                .filter(l -> !l.hasTransitMetadata()).collect(Collectors.toList());
        List<RouterOuterClass.PtLeg> ptLegs = path.getLegsList().stream()
                .filter(RouterOuterClass.PtLeg::hasTransitMetadata).collect(Collectors.toList());
        assertEquals(2, ptLegs.size());
        assertEquals(3, footLegs.size()); // access, transfer, and egress
        assertTrue(path.getDistanceMeters() > 0);
        assertTrue(path.getDurationMillis() > 0);

        // Check that foot legs contain proper info
        List<String> observedTravelSegmentTypes = Lists.newArrayList();
        List<String> expectedTravelSegmentTypes = Lists.newArrayList("ACCESS", "TRANSFER", "EGRESS");
        double observedDistanceMeters = 0;
        for (RouterOuterClass.PtLeg footLeg : footLegs) {
            assertTrue(footLeg.getStableEdgeIdsCount() > 0);
            assertTrue(footLeg.getArrivalTime().getSeconds() > footLeg.getDepartureTime().getSeconds());
            assertTrue(footLeg.getDistanceMeters() > 0);
            assertFalse(footLeg.getTravelSegmentType().isEmpty());
            observedTravelSegmentTypes.add(footLeg.getTravelSegmentType());
            observedDistanceMeters += footLeg.getDistanceMeters();
        }
        assertEquals(expectedTravelSegmentTypes, observedTravelSegmentTypes);

        // Check that PT legs contains proper info
        for (RouterOuterClass.PtLeg ptLeg : ptLegs) {
            assertTrue(ptLeg.getArrivalTime().getSeconds() > ptLeg.getDepartureTime().getSeconds());
            assertTrue(ptLeg.getStableEdgeIdsCount() > 0); // check that the GTFS link mapper worked
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
        assertEquals(path.getDistanceMeters(), observedDistanceMeters, 1.0E-10);


        // Check stops in first PT leg
        RouterOuterClass.PtLeg firstLeg = ptLegs.get(0);
        assertTrue(firstLeg.getTransitMetadata().getStopsList().size() > 0);
        for (RouterOuterClass.Stop stop : firstLeg.getTransitMetadata().getStopsList()) {
            assertFalse(stop.getStopId().isEmpty());
            assertEquals(1, TEST_GTFS_FILE_NAMES.stream().filter(f -> stop.getStopId().startsWith(f)).count());
            assertFalse(stop.getStopName().isEmpty());
            assertTrue(stop.hasPoint());
        }
    }

    @Test
    public void testAutoQuery() {
        final RouterOuterClass.StreetRouteReply response = routerStub.routeStreetMode(AUTO_REQUEST);
        // even without alternatives, we expect 2 auto paths, because we route with 2 auto profiles + combine results
        checkStreetBasedResponse(response, false, 2);
    }

    @Test
    public void testWalkQuery() {
        final RouterOuterClass.StreetRouteReply response = routerStub.routeStreetMode(WALK_REQUEST);
        checkStreetBasedResponse(response, false);
    }

    @Test
    public void testAutoQueryWithAlternatives() {
        final RouterOuterClass.StreetRouteReply response = routerStub.routeStreetMode(AUTO_REQUEST_WITH_ALTERNATIVES);
        checkStreetBasedResponse(response, true);
    }

    private static void checkStreetBasedResponse(RouterOuterClass.StreetRouteReply response, boolean alternatives) {
        checkStreetBasedResponse(response, alternatives, 1);
    }

    private static void checkStreetBasedResponse(RouterOuterClass.StreetRouteReply response,
                                                 boolean alternatives,
                                                 int expectedPathCount) {
        assertTrue(alternatives ? response.getPathsList().size() > 1 : response.getPathsList().size() == expectedPathCount);
        RouterOuterClass.StreetPath path = response.getPaths(0);
        assertTrue(path.getDurationMillis() > 0);
        assertTrue(path.getDistanceMeters() > 0);
        assertTrue(path.getStableEdgeIdsCount() > 0);
        assertEquals(path.getStableEdgeIdsCount(), path.getEdgeDurationsMillisCount());
        int totalDurationMillis = path.getEdgeDurationsMillisList().stream().mapToInt(Long::intValue).sum();
        assertEquals(path.getDurationMillis(), totalDurationMillis);
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
        RouterOuterClass.PtRouteRequest badPtRequest = PT_REQUEST_1.toBuilder()
                .setPoints(0, RouterOuterClass.Point.newBuilder().setLat(38.0).setLon(-94.0).build()).build();
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> {routerStub.routePt(badPtRequest);});
        assertSame(exception.getStatus().getCode(), Status.NOT_FOUND.getCode());
    }

    @Test
    public void testBadTimeTransit() {
        RouterOuterClass.PtRouteRequest badPtRequest = PT_REQUEST_1.toBuilder()
                .setEarliestDepartureTime(Timestamp.newBuilder().setSeconds(100).build()).build();
        StatusRuntimeException exception =
                assertThrows(StatusRuntimeException.class, () -> {routerStub.routePt(badPtRequest);});
        assertSame(exception.getStatus().getCode(), Status.NOT_FOUND.getCode());
    }
}

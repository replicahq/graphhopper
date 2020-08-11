package com.graphhopper.reader.gtfs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.Trip;
import com.graphhopper.http.WebHelper;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.Helper;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.TranslationMap;
import io.dropwizard.jersey.params.AbstractParam;
import io.dropwizard.jersey.params.InstantParam;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Path("route-pt")
public class CustomPtRouteResource {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final PtRouteResource ptRouteResource;
    private Map<String, String> gtfsLinkMappings;

    @Inject
    public CustomPtRouteResource(TranslationMap translationMap, GraphHopperStorage graphHopperStorage, LocationIndex locationIndex, GtfsStorage gtfsStorage, RealtimeFeed realtimeFeed) {
        ptRouteResource = new PtRouteResource(translationMap, graphHopperStorage, locationIndex, gtfsStorage, realtimeFeed);
        DB db = DBMaker.newFileDB(new File("transit_data/gtfs_link_mappings.db")).make();
        logger.info(db.toString());
        gtfsLinkMappings = db.getHashMap("gtfsLinkMappings");
        logger.info("Done loading GTFS link mappings. Total number of mappings: " + gtfsLinkMappings.size());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode route(@QueryParam("point") @Size(min=2,max=2) List<GHLocationParam> requestPoints,
                            @QueryParam("pt.earliest_departure_time") @NotNull InstantParam departureTimeParam,
                            @QueryParam("pt.profile_duration") DurationParam profileDuration,
                            @QueryParam("pt.arrive_by") @DefaultValue("false") boolean arriveBy,
                            @QueryParam("locale") String localeStr,
                            @QueryParam("pt.ignore_transfers") Boolean ignoreTransfers,
                            @QueryParam("pt.profile") Boolean profileQuery,
                            @QueryParam("pt.limit_solutions") Integer limitSolutions,
                            @QueryParam("pt.limit_street_time") DurationParam limitStreetTime) {
        StopWatch stopWatch = new StopWatch().start();
        List<GHLocation> points = requestPoints.stream().map(AbstractParam::get).collect(toList());
        Instant departureTime = departureTimeParam.get();

        Request request = new Request(points, departureTime);
        request.setArriveBy(arriveBy);
        Optional.ofNullable(profileQuery).ifPresent(request::setProfileQuery);
        Optional.ofNullable(profileDuration.get()).ifPresent(request::setMaxProfileDuration);
        Optional.ofNullable(ignoreTransfers).ifPresent(request::setIgnoreTransfers);
        Optional.ofNullable(localeStr).ifPresent(s -> request.setLocale(Helper.getLocale(s)));
        Optional.ofNullable(limitSolutions).ifPresent(request::setLimitSolutions);
        Optional.ofNullable(limitStreetTime.get()).ifPresent(request::setLimitStreetTime);

        GHResponse route = ptRouteResource.route(request);

        List<ResponsePath> pathsWithStableIds = Lists.newArrayList();
        for (ResponsePath path : route.getAll()) {
            List<Trip.Leg> legsWithStableIds = path.getLegs().stream()
                    .map(leg -> leg.type.equals("pt") ? getCustomPtLeg((Trip.PtLeg)leg) : leg)
                    .collect(toList());
            path.getLegs().clear();
            path.getLegs().addAll(legsWithStableIds);
            pathsWithStableIds.add(path);
        }

        GHResponse routeWithStableIds = new GHResponse();
        routeWithStableIds.addDebugInfo(route.getDebugInfo());
        routeWithStableIds.addErrors(route.getErrors());
        pathsWithStableIds.forEach(path -> routeWithStableIds.add(path));

        ObjectNode jsonResponse = WebHelper.jsonObject(routeWithStableIds, true, true, false, false, stopWatch.stop().getMillis());

        return jsonResponse;
    }

    // Create new version of PtLeg class that stores stable edge IDs in class var;
    // this var will automatically get added to JSON response
    public static class CustomPtLeg extends Trip.PtLeg {
        public final List<String> stableEdgeIds;

        public CustomPtLeg(Trip.PtLeg leg, List<String> stableEdgeIds) {
            super("pt", leg.isInSameVehicleAsPrevious, leg.trip_id, leg.route_id, leg.trip_headsign,
                    leg.stops, leg.distance, leg.travelTime, leg.geometry);
            this.stableEdgeIds = stableEdgeIds;
        }
    }

    private CustomPtLeg getCustomPtLeg(Trip.PtLeg leg) {
        List<Trip.Stop> stops = leg.stops;

        List<String> stableEdgeIds = Lists.newArrayList();
        for (int i = 0; i < stops.size() - 1; i++) {
            String stopPair = stops.get(i).stop_id + "," + stops.get(i + 1).stop_id;
            if (gtfsLinkMappings.containsKey(stopPair)) {
                stableEdgeIds.add(gtfsLinkMappings.get(stopPair));
            }
        }
        return new CustomPtLeg(leg, stableEdgeIds);
    }
}
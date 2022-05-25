package com.replica.api;

import com.google.rpc.Code;
import com.google.rpc.Status;
import com.graphhopper.routing.*;
import com.graphhopper.util.shapes.GHPoint;
import com.timgroup.statsd.StatsDClient;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import router.RouterOuterClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import static com.replica.metrics.MetricsHelper.applyRegionName;
import static com.replica.metrics.MetricsHelper.sendDatadogStats;
import static java.util.stream.Collectors.toList;

public class MatrixRouter {

    private static final Logger logger = LoggerFactory.getLogger(MatrixRouter.class);
    private final MatrixAPI matrixAPI;
    private final StatsDClient statsDClient;
    private String regionName;

    public MatrixRouter(MatrixAPI matrixAPI,
                        StatsDClient statsDClient,
                        String regionName) {
        this.matrixAPI = matrixAPI;
        this.statsDClient = statsDClient;
        this.regionName = regionName;
    }

    // TODO: Clean up code based on fix-it comments in PR #26
    public void routeMatrix(RouterOuterClass.MatrixRouteRequest request, StreamObserver<RouterOuterClass.MatrixRouteReply> responseObserver) {
        long startTime = System.currentTimeMillis();

        List<GHPoint> fromPoints = request.getFromPointsList().stream()
                .map(p -> new GHPoint(p.getLat(), p.getLon())).collect(toList());
        List<GHPoint> toPoints = request.getToPointsList().stream()
                .map(p -> new GHPoint(p.getLat(), p.getLon())).collect(toList());

        GHMRequest ghMatrixRequest = new GHMRequest();
        ghMatrixRequest.setFromPoints(fromPoints);
        ghMatrixRequest.setToPoints(toPoints);
        ghMatrixRequest.setOutArrays(new HashSet<>(request.getOutArraysList()));
        ghMatrixRequest.setProfile(request.getMode());
        ghMatrixRequest.setFailFast(request.getFailFast());

        try {
            GHMResponse ghMatrixResponse = matrixAPI.calc(ghMatrixRequest);

            if (ghMatrixRequest.getFailFast() && ghMatrixResponse.hasInvalidPoints()) {
                MatrixErrors matrixErrors = new MatrixErrors();
                matrixErrors.addInvalidFromPoints(ghMatrixResponse.getInvalidFromPoints());
                matrixErrors.addInvalidToPoints(ghMatrixResponse.getInvalidToPoints());
                throw new MatrixCalculationException(matrixErrors);
            }
            int from_len = ghMatrixRequest.getFromPoints().size();
            int to_len = ghMatrixRequest.getToPoints().size();
            List<List<Long>> timeList = new ArrayList(from_len);
            List<Long> timeRow;
            List<List<Long>> distanceList = new ArrayList(from_len);
            List<Long> distanceRow;
            Iterator<MatrixElement> iter = ghMatrixResponse.getMatrixElementIterator();
            MatrixErrors matrixErrors = new MatrixErrors();
            StringBuilder debugBuilder = new StringBuilder();
            debugBuilder.append(ghMatrixResponse.getDebugInfo());

            for (int fromIndex = 0; fromIndex < from_len; ++fromIndex) {
                timeRow = new ArrayList(to_len);
                timeList.add(timeRow);
                distanceRow = new ArrayList(to_len);
                distanceList.add(distanceRow);

                for (int toIndex = 0; toIndex < to_len; ++toIndex) {
                    if (!iter.hasNext()) {
                        throw new IllegalStateException("Internal error, matrix dimensions should be " + from_len + "x" + to_len + ", but failed to retrieve element (" + fromIndex + ", " + toIndex + ")");
                    }

                    MatrixElement element = iter.next();
                    if (!element.isConnected()) {
                        matrixErrors.addDisconnectedPair(element.getFromIndex(), element.getToIndex());
                    }

                    if (ghMatrixRequest.getFailFast() && matrixErrors.hasDisconnectedPairs()) {
                        throw new MatrixCalculationException(matrixErrors);
                    }

                    long time = element.getTime();
                    timeRow.add(time == Long.MAX_VALUE ? -1 : Math.round((double) time / 1000.0D));

                    double distance = element.getDistance();
                    distanceRow.add(distance == Double.MAX_VALUE ? -1 : Math.round(distance));

                    debugBuilder.append(element.getDebugInfo());
                }
            }

            List<RouterOuterClass.MatrixRow> timeRows = timeList.stream()
                    .map(row -> RouterOuterClass.MatrixRow.newBuilder().addAllValues(row).build()).collect(toList());
            List<RouterOuterClass.MatrixRow> distanceRows = distanceList.stream()
                    .map(row -> RouterOuterClass.MatrixRow.newBuilder().addAllValues(row).build()).collect(toList());

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getMode() + "_matrix", "api:grpc", "routes_found:true"};
            tags = applyRegionName(tags, regionName);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            RouterOuterClass.MatrixRouteReply result = RouterOuterClass.MatrixRouteReply.newBuilder().addAllTimes(timeRows).addAllDistances(distanceRows).build();
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error while completing GraphHopper matrix request! ", e);

            double durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            String[] tags = {"mode:" + request.getMode() + "_matrix", "api:grpc", "routes_found:false"};
            tags = applyRegionName(tags, regionName);
            sendDatadogStats(statsDClient, tags, durationSeconds);

            Status status = Status.newBuilder()
                    .setCode(Code.INTERNAL.getNumber())
                    .setMessage("GH internal error! Matrix request could not be completed.")
                    .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }
}

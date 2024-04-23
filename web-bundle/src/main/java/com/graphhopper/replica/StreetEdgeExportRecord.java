package com.graphhopper.replica;

public class StreetEdgeExportRecord {
    public String edgeId;
    public String humanReadableEdgeId;
    public int startVertexId;
    public int endVertexId;
    public double startLat;
    public double startLon;
    public double endLat;
    public double endLon;
    public String geometryString;
    public String streetName;
    public long distanceMillimeters;
    public long osmId;
    public int speedCms;
    public String flags;
    public int lanes;
    public String highwayTag;
    public long startOsmNode;
    public long endOsmNode;
    public String direction;
    public long osmRelationId;

    public StreetEdgeExportRecord(String edgeId, String humanReadableEdgeId, int startVertexId, int endVertexId,
                                  double startLat, double startLon, double endLat, double endLon,
                                  String geometryString, String streetName, long distanceMillimeters,
                                  long osmId, int speedCms, String flags, int lanes, String highwayTag,
                                  long startOsmNode, long endOsmNode, String direction, long osmRelationId) {
        this.edgeId = edgeId;
        this.humanReadableEdgeId = humanReadableEdgeId;
        this.startVertexId = startVertexId;
        this.endVertexId = endVertexId;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.geometryString = geometryString;
        this.streetName = streetName;
        this.distanceMillimeters = distanceMillimeters;
        this.osmId = osmId;
        this.speedCms = speedCms;
        this.flags = flags;
        this.lanes = lanes;
        this.highwayTag = highwayTag;
        this.startOsmNode = startOsmNode;
        this.endOsmNode = endOsmNode;
        this.direction = direction;
        this.osmRelationId = osmRelationId;
    }
}


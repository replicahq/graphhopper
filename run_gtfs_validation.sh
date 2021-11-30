#!/bin/bash
set -o errexit

# Assumes that folder containing gtfs files is mounted as volume at /gtfs/
export GTFS_FILE_LIST=$(ls ./gtfs/ | awk '{print "./gtfs/"$1}' | paste -s -d, -)
java -Xmx32g -Ddw.graphhopper.gtfs.file=$GTFS_FILE_LIST \
  -Ddw.graphhopper.validation=true -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar \
  com.graphhopper.http.GraphHopperApplication validate-gtfs gtfs_validation_gh_config.yaml

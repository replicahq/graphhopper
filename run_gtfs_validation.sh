#!/bin/bash
set -o errexit

# Assumes that folder containing gtfs files is mounted as volume at /gtfs/
export GTFS_FILE_LIST=$(ls ./gtfs/ | awk '{print "./gtfs/"$1}' | paste -s -d, -)

# Parse passed in GTFS schedule days string and substitute it for placeholder in validation GH config
GTFS_SCHEDULE_DAYS="$1"
sed -i -e "s/{{ GTFS_SCHEDULE_DAYS }}/${GTFS_SCHEDULE_DAYS}/g" ./configs/gtfs_validation_gh_config_phase_2.yaml

java -Xmx110g -Ddw.graphhopper.gtfs.file=$GTFS_FILE_LIST \
  -Ddw.graphhopper.validation=true -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar \
  com.graphhopper.http.GraphHopperApplication validate-gtfs ./configs/gtfs_validation_gh_config_phase_2.yaml

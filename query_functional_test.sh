#!/bin/bash

# todo: handle retries? Hopefully unnecessary
# todo: fix indentation of grpcurl request

set +ex

if [ "$#" -lt 1 ]; then
    echo "Usage: query_functional_test.sh TAG"
    exit 1
fi

TAG=$1

# Make sure the docker env is clean
docker rm --force $(docker ps --all -q)

# To run this script locally, you may need to run `docker pull $tag` from the model repo,
# where your credentials will be populated.
DOCKER_IMAGE_TAG="us.gcr.io/model-159019/gh:$TAG"

# Import data into graphhopper's internal format. It's necessary to move test data from /web before
# running import, because import paths are relative to base /graphhopper folder, unlike in `mvn test`
docker run \
    -v "$TMPDIR:/graphhopper/transit_data/"\
    --rm \
     "$DOCKER_IMAGE_TAG" \
     /bin/bash -c "cp -r ./web/test-data . && java -Xmx2g -Xms1g -XX:+UseG1GC -XX:MetaspaceSize=100M \
     -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar -server com.graphhopper.http.GraphHopperApplication import test_gh_config.yaml"

# Run link-mapping step
docker run \
    -v "$TMPDIR:/graphhopper/transit_data/"\
    --rm \
    "$DOCKER_IMAGE_TAG" \
    java -Xmx2g -Xms1g -XX:+UseG1GC -XX:MetaspaceSize=100M \
    -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar com.graphhopper.http.GraphHopperApplication gtfs_links test_gh_config.yaml

# Run server in background
docker run --rm --name functional_test_server -p 50051:50051 -p 8998:8998 -v "$TMPDIR:/graphhopper/transit_data/" \
    "$DOCKER_IMAGE_TAG" &

echo "Waiting for graphhopper server to start up"
sleep 30

# Grab the server ip:
SERVER=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' functional_test_server }})

touch "$TMPDIR"/street_responses.json
touch "$TMPDIR"/transit_responses.json

# Make street requests for each point in golden OD set for the micro_nor_cal region
while IFS=, read -r person_id lat lng lat_work lng_work tract tract_work ; do
  # Don't forget to skip the header line
  if [ "$person_id" != "person_id" ] ; then
grpcurl -d @ -plaintext $SERVER:50051 router.Router/RouteStreetMode > "$TMPDIR"/response.json <<EOM
{
"points":[{"lat":"$lat","lon":"$lng"},{"lat":"$lat_work","lon":"$lng_work"}],
"profile": "car"
}
EOM
    # Add JSON query result, appended with person_id field, to street_responses JSONL output file
    jq -c --arg person "$person_id" '. |= . + {"person_id": $person}' "$TMPDIR"/response.json >> "$TMPDIR"/street_responses.json
    # wc -l "$TMPDIR"/street_responses.json
    rm "$TMPDIR"/response.json
  fi
done < ./web/test-data/micro_nor_cal_golden_od_set.csv

# Make transit requests for each point in golden OD set for the micro_nor_cal region
while IFS=, read -r person_id lat lng lat_work lng_work tract tract_work ; do
  # Don't forget to skip the header line
  if [ "$person_id" != "person_id" ] ; then
grpcurl -d @ -plaintext localhost:50051 router.Router/RoutePt  > "$TMPDIR"/pt_response.json <<EOM
{
"points":[{"lat":"$lat","lon":"$lng"},{"lat":"$lat_work","lon":"$lng_work"}],
"earliest_departure_time":"2019-10-13T18:25:00Z",
"limit_solutions":4,
"max_profile_duration":10,
"beta_walk_time":1.5,
"limit_street_time_seconds":1440,
"use_pareto":false,
"betaTransfers":1440000
}
EOM
    # Add JSON query result, appended with person_id field, to street_responses JSONL output file
    jq -c --arg person "$person_id" '. |= . + {"person_id": $person}' "$TMPDIR"/pt_response.json >> "$TMPDIR"/transit_responses.json
    # wc -l "$TMPDIR"/street_responses.json
    rm "$TMPDIR"/pt_response.json
  fi
done < ./web/test-data/micro_nor_cal_golden_od_set.csv

docker kill functional_test_server

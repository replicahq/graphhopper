#!/bin/bash

# todo: handle retries/communications failures? Hopefully unnecessary with local docker setup
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
# running import, because import paths are relative to base /graphhopper folder, unlike in `mvn test`.
# Similarly, move the car custom model yamls to ../, as that's how we've specified its location in the
# configs to make mvn test happy
docker run \
    -v "$TMPDIR:/graphhopper/transit_data/" \
    -v "local_car_custom_model.yaml:local_car_custom_model.yaml" \
    -v "freeway_car_custom_model.yaml:freeway_car_custom_model.yaml" \
    --rm \
     "$DOCKER_IMAGE_TAG" \
     /bin/bash -c "cp -r ./web/test-data . && ls . && ls .. && \
     java -Xmx2g -Xms1g -XX:+UseG1GC -XX:MetaspaceSize=100M \
     -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar -server com.graphhopper.http.GraphHopperApplication import test_gh_config.yaml"

# Run link-mapping step
docker run \
    -v "$TMPDIR:/graphhopper/transit_data/" \
    -v "local_car_custom_model.yaml:local_car_custom_model.yaml" \
    -v "freeway_car_custom_model.yaml:freeway_car_custom_model.yaml" \
    --rm \
    "$DOCKER_IMAGE_TAG" \
    /bin/bash -c "ls . && ls .. && java -Xmx2g -Xms1g -XX:+UseG1GC -XX:MetaspaceSize=100M \
    -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar com.graphhopper.http.GraphHopperApplication gtfs_links test_gh_config.yaml"

# Run server in background
docker run --rm --log-driver=none --name functional_test_server -p 50051:50051 -p 8998:8998 \
    -v "$TMPDIR:/graphhopper/transit_data/" "$DOCKER_IMAGE_TAG" \
    -v "default_gh_config.yaml:/graphhopper/default_gh_config.yaml" \
    -v "local_car_custom_model.yaml:/graphhopper/local_car_custom_model.yaml" \
    -v "freeway_car_custom_model.yaml:/graphhopper/freeway_car_custom_model.yaml" &

echo "Waiting for graphhopper server to start up"
sleep 30

# Grab the server ip:
SERVER=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' functional_test_server }})

touch "$TMPDIR"/street_responses.json
touch "$TMPDIR"/transit_responses.json

echo "Running street queries for golden OD set"

# Make street requests for each point in golden OD set for the micro_nor_cal region
while IFS=, read -r person_id lat lng lat_work lng_work tract tract_work ; do
  # Don't forget to skip the header line
  if [ "$person_id" != "person_id" ] ; then
    START=$(python -c 'import time; print(int(time.time() * 1000))')
grpcurl -d @ -plaintext $SERVER:50051 router.Router/RouteStreetMode > "$TMPDIR"/response.json <<EOM
{
"points":[{"lat":"$lat","lon":"$lng"},{"lat":"$lat_work","lon":"$lng_work"}],
"profile": "car"
}
EOM
    END=$(python -c 'import time; print(int(time.time() * 1000))')
    QUERY_TIME=$((END-START))
    # Add JSON query result, appended with person_id and query time fields, to street_responses JSONL output file
    jq -c --arg person "$person_id" --arg query_time "$QUERY_TIME" '. |= . + {"person_id": $person} + {"query_time": $query_time}' "$TMPDIR"/response.json >> "$TMPDIR"/street_responses.json
    rm "$TMPDIR"/response.json
  fi
done < ./web/test-data/micro_nor_cal_golden_od_set.csv

echo "Done running street queries. Now running transit queries for golden OD set"

# Make transit requests for each point in golden OD set for the micro_nor_cal region
while IFS=, read -r person_id lat lng lat_work lng_work tract tract_work ; do
  # Don't forget to skip the header line
  if [ "$person_id" != "person_id" ] ; then
    START=$(python -c 'import time; print(int(time.time() * 1000))')
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
    END=$(python -c 'import time; print(int(time.time() * 1000))')
    QUERY_TIME=$((END-START))
    # Add JSON query result, appended with person_id and query time fields, to street_responses JSONL output file
    jq -c --arg person "$person_id" --arg query_time "$QUERY_TIME" '. |= . + {"person_id": $person} + {"query_time": $query_time}' "$TMPDIR"/pt_response.json >> "$TMPDIR"/transit_responses.json
    rm "$TMPDIR"/pt_response.json
  fi
done < ./web/test-data/micro_nor_cal_golden_od_set.csv

echo "Transit queries complete. Killing server container"

docker kill functional_test_server

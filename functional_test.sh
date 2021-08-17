#!/bin/bash

set +ex

if [ "$#" -lt 1 ]; then
    echo "Usage: functional_test.sh TAG"
    exit 1
fi

TAG=$1

TMPDIR=$(mktemp -d)

#make sure the env is clean:
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
     /bin/bash -c
     "cp -r ./web/test-data ./test-data &&
     java -Xmx2g -Xms1g -XX:+UseG1GC -XX:MetaspaceSize=100M
     -classpath web/target/graphhopper-web-1.0-SNAPSHOT.jar
     -server com.graphhopper.http.GraphHopperApplication import test_gh_config.yaml"

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

# greb the server ip:
SERVER= $(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' functional_test_server }})

# Make a request for a couple of points in the micro_nor_cal region
grpcurl  -d @ -plaintext $SERVER:50051 router.Router/RouteStreetMode > "$TMPDIR"/street_response.json <<EOM
{
"points":[{"lat":38.74891667931467,"lon":-121.29023848101498},{"lat":38.55518457319914,"lon":-121.43714698730038}],
"profile": "car"
}
EOM

#test if the client command exit status == 0

if [ $? != 0 ]
    then
        echo "ERROR: Car request FAILED"
        docker kill functional_test_server
        exit 1
fi

if ! [ -s "$TMPDIR"/street_response.json ] || ! jq -e .paths < "$TMPDIR"/street_response.json; then
    echo "Street response empty or not valid json:"
    cat "$TMPDIR"/street_response.json
    docker kill functional_test_server
    exit 1
fi

# Make a PT request too
grpcurl -d @ -plaintext localhost:50051 router.Router/RoutePt  > "$TMPDIR"/pt_response.json <<EOM
{
"points":[{"lat":38.74891667931467,"lon":-121.29023848101498},{"lat":38.55518457319914,"lon":-121.43714698730038}],
"earliest_departure_time":"2018-02-04T08:25:00Z",
"limit_solutions":4,
"max_profile_duration":10,
"beta_walk_time":1.5,
"limit_street_time_seconds":1440,
"use_pareto":false,
"betaTransfers":1440000
}
EOM

#test if the client command exit status == 0

if [ $? != 0 ]
    then
        echo "ERROR: PT request FAILED"
        docker kill functional_test_server
        exit 1
fi

if ! [ -s "$TMPDIR"/pt_response.json ] || ! jq -e .paths < "$TMPDIR"/pt_response.json; then
    echo "PT response empty or not valid json:"
    cat "$TMPDIR"/pt_response.json
    docker kill functional_test_server
    exit 1
fi

docker kill functional_test_server

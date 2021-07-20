#!/bin/bash
set -o errexit
set -o xtrace

if [[ -z "${MININORCAL_OSM_PATH}" || -z "${MININORCAL_GTFS_PATH}" ]]; then
  echo "MININORCAL_OSM_PATH or MININORCAL_GTFS_PATH env vars are not set! Set these appropraitely and try again"
  exit 1
else
  MININORCAL_OSM_PATH="${MININORCAL_OSM_PATH}"
  MININORCAL_GTFS_PATH="${MININORCAL_GTFS_PATH}"
fi

echo "Downloading OSM + GTFS data for mini_nor_cal test region"

gsutil -m cp $MININORCAL_OSM_PATH ./test-data/mini_nor_cal.osm.pbf
mkdir ./test-data/gtfs/
gsutil -m cp $MININORCAL_GTFS_PATH - | tar -C ./test-data/gtfs/ -xvf -
sed -i -e "s/TEST_OSM/.\/test-data\/mini_nor_cal.osm.pbf/g" ./test_gh_config.yaml
export GTFS_FILE_LIST=$(ls ./test-data/gtfs/ | awk '{print "./test-data/gtfs/"$1}' | paste -s -d, -)
sed -i -e "s/TEST_GTFS/${GTFS_FILE_LIST//\//\\/}/g" ./test_gh_config.yaml

echo "Download successful! Test config test_gh_config.yaml can now be used"

#!/bin/bash
set -o errexit

# bbox used to cut down mini_nor_cal's OSM to just north bay + Sacramento area, aka "micro_nor_cal"
# micro_nor_cal is large enough to meaningfully test routing, but reduces build time by ~75%
MICRO_NOR_CAL_BBOX="-122.30986499194101,37.91724446910281,-120.68388843649487,39.42040570561121"

# This script assumes that the following env vars are set, holding GCS paths to mini_nor_cal OSM + GTFS
if [[ -z "${MININORCAL_OSM_PATH}" || -z "${MININORCAL_GTFS_PATH}" ]]; then
  echo "MININORCAL_OSM_PATH or MININORCAL_GTFS_PATH env vars are not set! Set these appropraitely and try again"
  exit 1
else
  MININORCAL_OSM_PATH="${MININORCAL_OSM_PATH}"
  MININORCAL_GTFS_PATH="${MININORCAL_GTFS_PATH}"
fi

echo "Checking if OSM + GTFS test files exist; downloading if needed"
if [[ ! -f "./web/test-data/micro_nor_cal.osm.pbf" ]]; then
  echo "Downloading OSM data for mini_nor_cal test region"
  gsutil -m -o "GSUtil:parallel_process_count=1" cp $MININORCAL_OSM_PATH ./web/test-data/mini_nor_cal.osm.pbf
  echo "Cutting down downloaded mini_nor_cal OSM to smaller cutout"
  if ! command -v osmconvert; then
    if [[ $OSTYPE == 'darwin'* ]]; then
      brew install osmfilter
    else
      sudo apt install -y osmctools
    fi
  fi
  osmconvert ./web/test-data/mini_nor_cal.osm.pbf -b=$MICRO_NOR_CAL_BBOX --complete-ways --out-pbf > ./web/test-data/micro_nor_cal.osm.pbf
  rm ./web/test-data/mini_nor_cal.osm.pbf
fi

if [[ -z "$(ls -A ./web/test-data/gtfs)" ]]; then
  echo "Downloading GTFS data for mini_nor_cal test region"
  mkdir -p ./web/test-data/gtfs/
  gsutil -m -o "GSUtil:parallel_process_count=1" cp $MININORCAL_GTFS_PATH - | tar -C ./web/test-data/gtfs/ -xvf -
fi

echo "Removing GTFS files to create transit network defined in test_gtfs_files.txt"
for file in ./web/test-data/gtfs/*.zip; do
  filename="$(basename $file)"
  if ! grep -qxe "$filename" ./web/test-data/test_gtfs_files.txt; then
      echo "Deleting $filename"
      rm "./web/test-data/gtfs/$filename"
  fi
done

echo "Checking test_gh_config.yaml; updating paths to test OSM/GTFS if needed"
if grep -q TEST_OSM ./test_gh_config.yaml; then
  sed -i -e "s/TEST_OSM/.\/test-data\/micro_nor_cal.osm.pbf/g" ./test_gh_config.yaml
fi

if grep -q TEST_GTFS ./test_gh_config.yaml; then
  export GTFS_FILE_LIST=$(ls ./web/test-data/gtfs/ | awk '{print "./test-data/gtfs/"$1}' | paste -s -d, -)
  sed -i -e "s/TEST_GTFS/${GTFS_FILE_LIST//\//\\/}/g" ./test_gh_config.yaml
fi

echo "Setup complete! Tests can now be run with 'mvn test'"

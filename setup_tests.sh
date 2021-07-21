#!/bin/bash
set -o errexit

if [[ -z "${MININORCAL_OSM_PATH}" || -z "${MININORCAL_GTFS_PATH}" ]]; then
  echo "MININORCAL_OSM_PATH or MININORCAL_GTFS_PATH env vars are not set! Set these appropraitely and try again"
  exit 1
else
  MININORCAL_OSM_PATH="${MININORCAL_OSM_PATH}"
  MININORCAL_GTFS_PATH="${MININORCAL_GTFS_PATH}"
fi

echo "Checking if OSM + GTFS test files exist; downloading if needed"
if [[ -z "./web/test-data/mini_nor_cal.osm.pbf" ]]; then
  echo "Downloading OSM data for mini_nor_cal test region"
  gsutil -m -o "GSUtil:parallel_process_count=1" cp $MININORCAL_OSM_PATH ./web/test-data/mini_nor_cal.osm.pbf
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
  sed -i -e "s/TEST_OSM/.\/test-data\/mini_nor_cal.osm.pbf/g" ./test_gh_config.yaml
fi

if grep -q TEST_GTFS ./test_gh_config.yaml; then
  export GTFS_FILE_LIST=$(ls ./web/test-data/gtfs/ | awk '{print "./test-data/gtfs/"$1}' | paste -s -d, -)
  sed -i -e "s/TEST_GTFS/${GTFS_FILE_LIST//\//\\/}/g" ./test_gh_config.yaml
fi

echo "Setup complete! Tests can now be run with 'mvn test'"

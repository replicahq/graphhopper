#!/bin/bash
set -o errexit

echo "Checking if micro_nor_cal test files have been properly generated with setup_tests.sh"
if [[ ! -f "./web/test-data/micro_nor_cal.osm.pbf" ]]; then
  echo "micro_nor_cal OSM file not found! Make sure you run setup_tests.sh before running tests"
  exit 1
fi

if [[ -z "$(ls -A ./web/test-data/gtfs)" ]]; then
  echo "micro_nor_cal GTFS files not found! Make sure you run setup_tests.sh before running tests"
  exit 1
fi

echo "Checking that paths to micro_nor_cal test files have been added to test_gh_config.yaml"
if grep -q TEST_OSM ./test_gh_config.yaml || grep -q TEST_GTFS ./test_gh_config.yaml; then
  echo "test_gh_config.yaml is not pointing to micro_nor_cal test files! Run setup_tests.sh before running tests"
  exit 1
fi

echo "Checks complete; setup_tests.sh has been run, proceeding with tests"

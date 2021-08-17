#!/bin/bash

bq query --format=csv --use_legacy_sql=false --max_rows=1500 \
'WITH commuters AS (
  SELECT
    person_id, lat, lng, lat_work, lng_work, TRACT, TRACT_work,
    RANK() OVER (
      PARTITION BY TRACT, TRACT_work ORDER BY FARM_FINGERPRINT(person_id)
    ) AS index
  FROM model-159019.population.mini_nor_cal_3ff7a186b3d39b0fbf8a6d6fa63a03666a373493
  WHERE
    resident_type = "core"
    AND commute_mode = "transit"
    AND -122.41229018000416 < lng_work
    AND lng_work < -120.49584285533076
    AND 37.75738096439945 < lat_work
    AND lat_work < 39.52415953258036
)
SELECT * EXCEPT (index) FROM commuters
WHERE index = 1
LIMIT 1000
' > micro_nor_cal_golden_od_set.csv

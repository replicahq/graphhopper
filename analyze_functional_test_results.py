from datetime import datetime
from typing import Tuple
import copy
import json
import numpy as np
import sys
import time


NEW_ROUTES_FOUND_THRESHOLD = 0.05
OLD_ROUTES_NOT_FOUND_THRESHOLD = 0.05
MATCHED_ROUTES_THRESHOLD = 0.95
TRAVEL_TIME_MPC_THRESHOLD = 0.05
TRAVEL_TIME_MEAN_APC_THRESHOLD = 0.05
TRANSIT_RATIO_MPC_THRESHOLD = 0.05
TRANSIT_RATIO_MEAN_APC_THRESHOLD = 0.05
STREET_MEAN_QUERY_TIME_THRESHOLD_MS = 300
STREET_90TH_PERCENTILE_QUERY_TIME_THRESHOLD_MS = 750
TRANSIT_MEAN_QUERY_TIME_THRESHOLD_MS = 500
TRANSIT_90TH_PERCENTILE_QUERY_TIME_THRESHOLD_MS = 1000


def import_query_results(
    street_path: str = "street_responses.json",
    transit_path: str = "transit_responses.json",
) -> Tuple[dict, dict]:
    with open(street_path) as street_response_file:
        street_results_json = [
            json.loads(jline) for jline in street_response_file.read().splitlines()
        ]

    with open(transit_path) as transit_response_file:
        transit_results_json = [
            json.loads(jline) for jline in transit_response_file.read().splitlines()
        ]

    street_results_map = {res["person_id"]: res for res in street_results_json}
    transit_results_map = {res["person_id"]: res for res in transit_results_json}
    return street_results_map, transit_results_map


def get_unix_timestamp(datetime_string: str) -> int:
    return time.mktime(
        datetime.strptime(datetime_string, "%Y-%m-%dT%H:%M:%SZ").timetuple()
    )


def calculate_mean_query_time(response_set: dict) -> float:
    return (
        np.array([r["query_time"] for r in response_set.values()])
        .astype(np.int64)
        .mean()
    )


def calculate_query_time_90th_percentile(response_set: dict) -> float:
    return np.percentile(
        np.array([r["query_time"] for r in response_set.values()]).astype(np.int64), 90
    )


def calculate_transit_ratio(response: dict) -> float:
    first_path = response["paths"][0]
    transit_legs = filter(lambda x: x.has_key("transit_metadata"), first_path["legs"])
    transit_time_millis = sum(
        (
            get_unix_timestamp(leg["arrival_time"])
            - get_unix_timestamp(leg["departure_time"])
        )
        * 1000
        for leg in transit_legs
    )
    return transit_time_millis / int(first_path["duration_millis"])


def percent_new_routes_found(
    golden_response_set: dict, responses_to_validate: dict
) -> float:
    new_routes_count = len(responses_to_validate.keys() - golden_response_set.keys())
    return new_routes_count / len(golden_response_set)


def percent_old_routes_not_found(
    golden_response_set: dict, responses_to_validate: dict
) -> float:
    old_routes_not_found_count = len(
        golden_response_set.keys() - responses_to_validate.keys()
    )
    return old_routes_not_found_count / len(golden_response_set)


def percent_matched_routes(
    golden_response_set: dict, responses_to_validate: dict
) -> float:
    matched_count = 0
    for person_id in golden_response_set.keys() & responses_to_validate.keys():
        if person_id in golden_response_set and person_id in responses_to_validate:
            golden_entry = golden_response_set[person_id]
            to_validate_entry = responses_to_validate[person_id]
            # remove query_time field before doing comparison
            golden_compare = {
                copy.deepcopy(k): copy.deepcopy(v)
                for k, v in golden_entry.items()
                if k != "query_time"
            }
            to_validate_compare = {
                copy.deepcopy(k): copy.deepcopy(v)
                for k, v in to_validate_entry.items()
                if k != "query_time"
            }
            # round distance_meters to nearest meter, because it seems to be slightly inconsistent/nondeterministic
            for path in golden_compare["paths"]:
                path["distance_meters"] = int(path["distance_meters"])
            for path in to_validate_compare["paths"]:
                path["distance_meters"] = int(path["distance_meters"])
            if golden_compare == to_validate_compare:
                matched_count += 1
    return matched_count / len(golden_response_set)


# Note: The following 2 checks only consider routes that are found across both runs,
# and only consider the first path of each properly matched response
# Mean percent change over n response paths = (1/n) * sum over n((T_new - T_old) / T_old)
# Mean absolute percent change over n response paths = (1/n) * sum over n(abs((T_new - T_old) / T_old))
def travel_time_mean_percent_change(
    golden_response_set: dict,
    responses_to_validate: dict,
    is_transit: bool,
    absolute: bool,
) -> float:
    matched_count = 0
    sum_of_changes = 0.0
    for person_id, golden_response in golden_response_set.items():
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            first_golden = golden_response["paths"][0]
            first_to_compare = to_compare["paths"][0]
            if is_transit:
                golden_travel_time = sum(
                    (
                        get_unix_timestamp(leg["arrival_time"])
                        - get_unix_timestamp(leg["departure_time"])
                    )
                    * 1000
                    for leg in (first_golden["legs"])
                )
                to_compare_travel_time = sum(
                    (
                        get_unix_timestamp(leg["arrival_time"])
                        - get_unix_timestamp(leg["departure_time"])
                    )
                    * 1000
                    for leg in (first_to_compare["legs"])
                )
            else:
                golden_travel_time = first_golden["duration_millis"]
                to_compare_travel_time = first_to_compare["duration_millis"]
            if absolute:
                sum_of_changes += abs(
                    (int(to_compare_travel_time) - int(golden_travel_time))
                    / int(golden_travel_time)
                )
            else:
                sum_of_changes += (
                    int(to_compare_travel_time) - int(golden_travel_time)
                ) / int(golden_travel_time)
    return (1 / matched_count) * sum_of_changes


def transit_ratio_mean_percent_change(
    golden_response_set: dict, responses_to_validate: dict, absolute: bool
) -> float:
    matched_count = 0
    sum_of_changes = 0.0
    for person_id, golden_response in golden_response_set.items():
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            golden_ratio = calculate_transit_ratio(golden_response)
            to_compare_ratio = calculate_transit_ratio(to_compare)
            if absolute:
                sum_of_changes += abs((to_compare_ratio - golden_ratio) / golden_ratio)
            else:
                sum_of_changes += (to_compare_ratio - golden_ratio) / golden_ratio
    return (1 / matched_count) * sum_of_changes


def run_all_validations(
    golden_response_set: dict, responses_to_validate: dict, is_transit: bool
):
    validation_results = {}
    validation_results["new_routes_found"] = percent_new_routes_found(
        golden_response_set, responses_to_validate
    )
    validation_results["old_routes_not_found"] = percent_old_routes_not_found(
        golden_response_set, responses_to_validate
    )
    validation_results["matched_routes"] = percent_matched_routes(
        golden_response_set, responses_to_validate
    )
    validation_results["travel_time_mpc"] = travel_time_mean_percent_change(
        golden_response_set, responses_to_validate, is_transit, False
    )
    validation_results["travel_time_mean_apc"] = travel_time_mean_percent_change(
        golden_response_set, responses_to_validate, is_transit, True
    )
    if is_transit:
        validation_results["transit_ratio_mpc"] = transit_ratio_mean_percent_change(
            golden_response_set, responses_to_validate, False
        )
        validation_results[
            "transit_ratio_mean_apc"
        ] = transit_ratio_mean_percent_change(
            golden_response_set, responses_to_validate, True
        )

    validation_results["mean_query_time"] = calculate_mean_query_time(
        responses_to_validate
    )
    validation_results[
        "90th_percentile_query_time"
    ] = calculate_query_time_90th_percentile(responses_to_validate)

    print("Results of validation: \n" + str(validation_results))

    assert validation_results["new_routes_found"] <= NEW_ROUTES_FOUND_THRESHOLD
    assert validation_results["old_routes_not_found"] <= OLD_ROUTES_NOT_FOUND_THRESHOLD
    assert validation_results["matched_routes"] >= MATCHED_ROUTES_THRESHOLD
    assert abs(validation_results["travel_time_mpc"]) <= TRAVEL_TIME_MPC_THRESHOLD
    assert validation_results["travel_time_mean_apc"] <= TRAVEL_TIME_MEAN_APC_THRESHOLD
    if is_transit:
        assert (
            abs(validation_results["transit_ratio_mpc"]) <= TRANSIT_RATIO_MPC_THRESHOLD
        )
        assert (
            validation_results["transit_ratio_mean_apc"]
            <= TRANSIT_RATIO_MEAN_APC_THRESHOLD
        )
        assert (
            validation_results["mean_query_time"]
            <= TRANSIT_MEAN_QUERY_TIME_THRESHOLD_MS
        )
        assert (
            validation_results["90th_percentile_query_time"]
            <= TRANSIT_90TH_PERCENTILE_QUERY_TIME_THRESHOLD_MS
        )
    else:
        assert (
            validation_results["mean_query_time"] <= STREET_MEAN_QUERY_TIME_THRESHOLD_MS
        )
        assert (
            validation_results["90th_percentile_query_time"]
            <= STREET_90TH_PERCENTILE_QUERY_TIME_THRESHOLD_MS
        )


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print(
            "Improper number of arguments provided! Be sure 4 response sets are being passed as input"
        )
        raise
    golden_street_responses, golden_transit_responses = import_query_results(
        sys.argv[1], sys.argv[2]
    )
    to_validate_street_responses, to_validate_transit_responses = import_query_results(
        sys.argv[3], sys.argv[4]
    )
    print("Running validations for street responses")
    run_all_validations(golden_street_responses, to_validate_street_responses, False)
    print("Running validations for transit responses")
    run_all_validations(golden_transit_responses, to_validate_transit_responses, True)

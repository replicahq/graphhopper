from datetime import datetime
import json
import sys
import time

def import_query_results(street_path='street_responses.json', transit_path='transit_responses.json'):
    with open(street_path) as street_response_file:
        street_results_json = [json.loads(jline) for jline in street_response_file.read().splitlines()]

    with open(transit_path) as transit_response_file:
        transit_results_json = [json.loads(jline) for jline in transit_response_file.read().splitlines()]

    street_results_map = dict(map(lambda x: (x['person_id'], x), street_results_json))
    transit_results_map = dict(map(lambda x: (x['person_id'], x), transit_results_json))
    return street_results_map, transit_results_map

def get_unix_timestamp(datetime_string):
    return time.mktime(datetime.strptime(datetime_string, '%Y-%m-%dT%H:%M:%SZ').timetuple())

def calculate_transit_ratio(response):
    first_path = response['paths'][0]
    transit_time_millis = sum((get_unix_timestamp(leg['arrival_time']) - get_unix_timestamp(leg['departure_time'])) * 1000 for leg in first_path['pt_legs'])
    return transit_time_millis / int(first_path['duration_millis'])

def percent_new_routes_found(golden_response_set, responses_to_validate):
    new_routes_count = len(responses_to_validate.keys() - golden_response_set.keys())
    return new_routes_count / len(golden_response_set)

def percent_old_routes_not_found(golden_response_set, responses_to_validate):
    old_routes_not_found_count = len(golden_response_set.keys() - responses_to_validate.keys())
    return old_routes_not_found_count / len(golden_response_set)

def percent_matched_routes(golden_response_set, responses_to_validate):
    matched_count = 0
    for person_id in golden_response_set.keys() & responses_to_validate.keys():
        if person_id in golden_response_set and person_id in responses_to_validate:
            golden_entry = golden_response_set[person_id]
            to_validate_entry = responses_to_validate[person_id]
            # round distance_meters to nearest meter, because it seems to be slightly inconsistent/nondeterministic
            for path in golden_entry['paths']:
                path['distance_meters'] = int(path['distance_meters'])
            for path in to_validate_entry['paths']:
                path['distance_meters'] = int(path['distance_meters'])
            if golden_entry == to_validate_entry:
                matched_count += 1
    return matched_count / len(golden_response_set)

# Note: The following 4 checks only consider routes that are found across both runs,
# and only consider the first path of each properly matched response
# Mean percent change over n response paths = (1/n) * sum over n((T_new - T_old) / T_old)
# Mean absolute percent change over n response paths = (1/n) * sum over n(abs((T_new - T_old) / T_old))
def travel_time_mean_percent_change(golden_response_set, responses_to_validate, is_transit):
    matched_count = 0
    sum_of_changes = 0.0
    for person_id in golden_response_set.keys():
        golden_response = golden_response_set.get(person_id)
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            first_golden = golden_response['paths'][0]
            first_to_compare = to_compare['paths'][0]
            if is_transit:
                golden_travel_time = sum((get_unix_timestamp(leg['arrival_time']) - get_unix_timestamp(leg['departure_time'])) * 1000 for leg in (first_golden['pt_legs'] + first_golden['foot_legs']))
                to_compare_travel_time = sum((get_unix_timestamp(leg['arrival_time']) - get_unix_timestamp(leg['departure_time'])) * 1000 for leg in (first_to_compare['pt_legs'] + first_to_compare['foot_legs']))
            else:
                golden_travel_time = first_golden['duration_millis']
                to_compare_travel_time = first_to_compare['duration_millis']
            sum_of_changes += (int(to_compare_travel_time) - int(golden_travel_time)) / int(golden_travel_time)
    return (1 / matched_count) * sum_of_changes

def transit_ratio_mean_percent_change(golden_response_set, responses_to_validate):
    matched_count = 0
    sum_of_changes = 0.0
    for person_id in golden_response_set.keys():
        golden_response = golden_response_set.get(person_id)
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            golden_ratio = calculate_transit_ratio(golden_response)
            to_compare_ratio = calculate_transit_ratio(to_compare)
            sum_of_changes += (to_compare_ratio - golden_ratio) / golden_ratio
    return (1 / matched_count) * sum_of_changes

def travel_time_mean_absolute_percent_change(golden_response_set, responses_to_validate, is_transit):
    matched_count = 0
    sum_of_changes = 0.0
    for person_id in golden_response_set.keys():
        golden_response = golden_response_set.get(person_id)
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            first_golden = golden_response['paths'][0]
            first_to_compare = to_compare['paths'][0]
            if is_transit:
                golden_travel_time = sum((get_unix_timestamp(leg['arrival_time']) - get_unix_timestamp(leg['departure_time'])) * 1000 for leg in (first_golden['pt_legs'] + first_golden['foot_legs']))
                to_compare_travel_time = sum((get_unix_timestamp(leg['arrival_time']) - get_unix_timestamp(leg['departure_time'])) * 1000 for leg in (first_to_compare['pt_legs'] + first_to_compare['foot_legs']))
            else:
                golden_travel_time = first_golden['duration_millis']
                to_compare_travel_time = first_to_compare['duration_millis']
            sum_of_changes += abs((int(to_compare_travel_time) - int(golden_travel_time)) / int(golden_travel_time))
    return (1 / matched_count) * sum_of_changes

def transit_ratio_mean_absolute_percent_change(golden_response_set, responses_to_validate):
    matched_count = 0
    sum_of_changes = 0.0
    for person_id in golden_response_set.keys():
        golden_response = golden_response_set.get(person_id)
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            golden_ratio = calculate_transit_ratio(golden_response)
            to_compare_ratio = calculate_transit_ratio(to_compare)
            sum_of_changes += abs((to_compare_ratio - golden_ratio) / golden_ratio)
    return (1 / matched_count) * sum_of_changes

# todo: add print statements to show all results before/during actual assertions
def run_all_validations(golden_response_set, responses_to_validate, is_transit):
    validation_results = {}
    validation_results['new_routes_found'] = percent_new_routes_found(golden_response_set, responses_to_validate)
    validation_results['old_routes_not_found'] = percent_old_routes_not_found(golden_response_set, responses_to_validate)
    validation_results['matched_routes'] = percent_matched_routes(golden_response_set, responses_to_validate)
    validation_results['travel_time_mpc'] = travel_time_mean_percent_change(golden_response_set, responses_to_validate, is_transit)
    validation_results['travel_time_mean_apc'] = travel_time_mean_absolute_percent_change(golden_response_set, responses_to_validate, is_transit)
    if is_transit:
        validation_results['transit_ratio_mpc'] = transit_ratio_mean_percent_change(golden_response_set, responses_to_validate)
        validation_results['transit_ratio_mean_apc'] = transit_ratio_mean_absolute_percent_change(golden_response_set, responses_to_validate)

    print("Results of validation: \n" + str(validation_results))

    assert validation_results['new_routes_found'] <= 0.05
    assert validation_results['old_routes_not_found'] <= 0.05
    assert validation_results['matched_routes'] >= 0.95
    assert abs(validation_results['travel_time_mpc']) <= 0.05
    assert validation_results['travel_time_mean_apc'] <= 0.05
    if is_transit:
        assert abs(validation_results['transit_ratio_mpc']) <= 0.05
        assert validation_results['transit_ratio_mean_apc'] <= 0.05

if __name__ == '__main__':
    if len(sys.argv) != 5:
        print("Improper number of arguments provided! Be sure 4 response sets are being passed as input")
        raise
    golden_street_responses, golden_transit_responses = import_query_results(sys.argv[1], sys.argv[2])
    to_validate_street_responses, to_validate_transit_responses = import_query_results(sys.argv[3], sys.argv[4])
    print("Running validations for street responses")
    run_all_validations(golden_street_responses, to_validate_street_responses, False)
    print("Running validations for transit responses")
    run_all_validations(golden_transit_responses, to_validate_transit_responses, True)

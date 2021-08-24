import json

def import_query_results(street_path='street_responses.json', transit_path='transit_responses.json'):
    with open(street_path) as street_response_file:
        street_results_json = [json.loads(jline) for jline in street_response_file.read().splitlines()]

    with open(transit_path) as transit_response_file:
        transit_results_json = [json.loads(jline) for jline in transit_response_file.read().splitlines()]

    street_results_map = dict(map(lambda x: (x['person_id'], x), street_results_json))
    transit_results_map = dict(map(lambda x: (x['person_id'], x), transit_results_json))
    return street_results_map, transit_results_map

def calculate_transit_ratio(response):
    first_path = response.paths[0]
    transit_time_millis = sum((leg.arrival_time.seconds - leg.departure_time.seconds) * 1000 for leg in first_path.pt_legs)
    return transit_time_millis / first_path.duration_millis

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
            if golden_response_set[person_id] == responses_to_validate[person_id]:
                matched_count += 1
    return matched_count / len(golden_response_set)

# Note: The following 4 checks only consider routes that are found across both runs,
# and only consider the first path of each properly matched response
# Mean percent change over n response paths = (1/n) * sum over n((T_new - T_old) / T_old)
# Mean absolute percent change over n response paths = (1/n) * sum over n(abs((T_new - T_old) / T_old))

def travel_time_mean_percent_change(golden_response_set, responses_to_validate):
    matched_count = 0
    sum_of_changes = 0.0
    for person_id in golden_response_set.keys():
        golden_response = golden_response_set.get(person_id)
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            sum_of_changes += (to_compare.paths[0].duration_millis - golden_response.paths[0].duration_millis) / golden_response.paths[0].duration_millis
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

def travel_time_mean_absolute_percent_change(golden_response_set, responses_to_validate):
    matched_count = 0
    sum_of_changes = 0.0
    for person_id in golden_response_set.keys():
        golden_response = golden_response_set.get(person_id)
        to_compare = responses_to_validate.get(person_id)
        if to_compare:
            matched_count += 1
            sum_of_changes += abs((to_compare.paths[0].duration_millis - golden_response.paths[0].duration_millis) / golden_response.paths[0].duration_millis)
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
def run_all_validations(golden_response_set, responses_to_validate):
    assert percent_new_routes_found(golden_response_set, responses_to_validate) <= 0.05
    assert percent_old_routes_not_found(golden_response_set, responses_to_validate) <= 0.05
    assert percent_matched_routes(golden_response_set, responses_to_validate) >= 0.95
    assert abs(travel_time_mean_percent_change(golden_response_set, responses_to_validate)) <= 0.05
    assert abs(transit_ratio_mean_percent_change(golden_response_set, responses_to_validate)) <= 0.05
    assert travel_time_mean_absolute_percent_change(golden_response_set, responses_to_validate) <= 0.05
    assert transit_ratio_mean_absolute_percent_change(golden_response_set, responses_to_validate) <= 0.05

if __name__ == '__main__':
    golden_street_responses, golden_transit_responses = import_query_results()
    to_validate_street_responses, to_validate_transit_responses = import_query_results()
    print("Running validations for street responses")
    run_all_validations(golden_street_responses, to_validate_street_responses)
    print("Running validations for transit responses")
    run_all_validations(golden_transit_responses, to_validate_transit_responses)

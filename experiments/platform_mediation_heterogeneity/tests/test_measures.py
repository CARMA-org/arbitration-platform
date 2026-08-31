"""Dissimilarity-measure tests on hand-constructed examples with obvious answers."""
import math

from pilotlib import measures

CATS = ["research", "code_review", "doc_processing", "monitoring"]
RES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]


def test_tv_identical_is_zero():
    assert measures.tv([0.25, 0.25, 0.25, 0.25], [0.25, 0.25, 0.25, 0.25]) == 0.0


def test_tv_disjoint_is_one():
    assert measures.tv([1, 0, 0, 0], [0, 1, 0, 0]) == 1.0


def test_tv_known_value():
    # 0.5 * (|0.5-0.25| + |0.5-0.25| + |0-0.25| + |0-0.25|) = 0.5 * 1.0 = 0.5
    assert abs(measures.tv([0.5, 0.5, 0, 0], [0.25, 0.25, 0.25, 0.25]) - 0.5) < 1e-12


def test_mean_pairwise_tv_two_groups():
    # three agents on archetype 0, three on archetype 1: 9 cross pairs at TV=1, 6 same
    # pairs at TV=0, over 15 pairs -> 9/15 = 0.6
    vecs = [[1, 0, 0, 0]] * 3 + [[0, 1, 0, 0]] * 3
    assert abs(measures.mean_pairwise_tv(vecs) - 0.6) < 1e-12


def test_mean_pairwise_tv_identical_is_zero():
    vecs = [[0.25, 0.25, 0.25, 0.25]] * 6
    assert measures.mean_pairwise_tv(vecs) == 0.0


def test_task_frequency_vector():
    types = ["research", "research", "code_review", "monitoring"]
    v = measures.task_frequency_vector(types, CATS)
    assert v == [0.5, 0.25, 0.0, 0.25]


def test_resource_demand_vector_normalizes():
    md = {"COMPUTE": 10, "MEMORY": 10, "API_CREDITS": 0, "DATASET": 0}
    v = measures.resource_demand_vector(md, RES)
    assert v == [0.5, 0.5, 0.0, 0.0]


def test_resource_demand_vector_zero_total():
    md = {r: 0 for r in RES}
    assert measures.resource_demand_vector(md, RES) == [0.0, 0.0, 0.0, 0.0]


def test_normalized_entropy_uniform_is_one():
    assert abs(measures.normalized_entropy([0.25, 0.25, 0.25, 0.25]) - 1.0) < 1e-12


def test_normalized_entropy_point_mass_is_zero():
    assert measures.normalized_entropy([1.0, 0.0, 0.0, 0.0]) == 0.0


def test_normalized_entropy_two_of_four():
    # H = log 2, normalized by log 4 = 0.5
    assert abs(measures.normalized_entropy([0.5, 0.5, 0, 0]) - 0.5) < 1e-12


def test_centroid_and_distance():
    vecs = [[1, 0, 0, 0], [0, 1, 0, 0]]
    c = measures.centroid(vecs)
    assert c == [0.5, 0.5, 0.0, 0.0]
    # each vector is TV 0.5 from centroid
    assert abs(measures.mean_distance_to_centroid(vecs) - 0.5) < 1e-12


def test_unique_archetype_count():
    assert measures.unique_archetype_count(["research"] * 8, CATS) == 1
    assert measures.unique_archetype_count(
        ["research", "code_review", "doc_processing", "monitoring"] * 2, CATS) == 4


def test_workload_dissimilarity_bundle_all_identical():
    att = [["research", "code_review", "doc_processing", "monitoring"] * 2] * 6
    md = [{"COMPUTE": 5, "MEMORY": 5, "API_CREDITS": 5, "DATASET": 5}] * 6
    d = measures.workload_dissimilarity(att, md, CATS, RES)
    assert d["task_mixture_tv_mean_pairwise"] == 0.0
    assert d["resource_demand_tv_mean_pairwise"] == 0.0
    assert d["frac_agents_all_four_archetypes"] == 1.0
    assert abs(d["task_entropy_mean"] - 1.0) < 1e-12

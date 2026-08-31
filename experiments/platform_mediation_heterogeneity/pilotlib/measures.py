"""Realized workload-dissimilarity measures.

All distances are total variation ``TV(p, q) = 0.5 * sum_k |p_k - q_k|`` between
probability vectors, which lies in ``[0, 1]``. Two families of vectors are used:

* task-mixture vectors -- each agent's normalized frequency over the four task
  archetypes;
* resource-demand vectors -- each agent's mandatory resource-demand vector
  normalized to sum to one over the four resources.

The primary dissimilarity measure for the pilot is the mean pairwise resource-
demand TV. Generator concentration is an experimental input; realized resource-
demand dissimilarity is the measured quantity.
"""
import math


def _normalize(counts):
    total = float(sum(counts))
    if total <= 0:
        return [0.0 for _ in counts]
    return [c / total for c in counts]


def task_frequency_vector(task_types, categories):
    counts = [sum(1 for t in task_types if t == c) for c in categories]
    return _normalize(counts)


def resource_demand_vector(mandatory_demand, resources):
    counts = [float(mandatory_demand[r]) for r in resources]
    return _normalize(counts)


def tv(p, q):
    return 0.5 * sum(abs(a - b) for a, b in zip(p, q))


def mean_pairwise_tv(vectors):
    n = len(vectors)
    if n < 2:
        return 0.0
    total = 0.0
    pairs = 0
    for i in range(n):
        for k in range(i + 1, n):
            total += tv(vectors[i], vectors[k])
            pairs += 1
    return total / pairs


def centroid(vectors):
    if not vectors:
        return []
    m = len(vectors[0])
    return [sum(v[j] for v in vectors) / len(vectors) for j in range(m)]


def mean_distance_to_centroid(vectors):
    """Mean TV distance from each vector to the population centroid."""
    if len(vectors) < 1:
        return 0.0
    c = centroid(vectors)
    return sum(tv(v, c) for v in vectors) / len(vectors)


def normalized_entropy(p):
    """Shannon entropy of ``p`` divided by ``log(len(p))`` (in ``[0, 1]``)."""
    k = len(p)
    if k <= 1:
        return 0.0
    h = 0.0
    for x in p:
        if x > 0:
            h -= x * math.log(x)
    return h / math.log(k)


def unique_archetype_count(task_types, categories):
    return sum(1 for c in categories if any(t == c for t in task_types))


def workload_dissimilarity(agent_task_types, mandatory_demand, categories, resources):
    """Bundle the per-scenario workload measures into one dict."""
    task_vecs = [task_frequency_vector(t, categories) for t in agent_task_types]
    res_vecs = [resource_demand_vector(md, resources) for md in mandatory_demand]
    uniq = [unique_archetype_count(t, categories) for t in agent_task_types]
    n = len(agent_task_types)
    return {
        "task_mixture_tv_mean_pairwise": mean_pairwise_tv(task_vecs),
        "resource_demand_tv_mean_pairwise": mean_pairwise_tv(res_vecs),
        "resource_centroid_distance_mean": mean_distance_to_centroid(res_vecs),
        "task_entropy_mean": sum(normalized_entropy(p) for p in task_vecs) / n if n else 0.0,
        "unique_archetypes_per_agent_mean": sum(uniq) / n if n else 0.0,
        "frac_agents_all_four_archetypes": sum(1 for u in uniq if u == len(categories)) / n if n else 0.0,
        "unique_archetypes_per_agent": uniq,
    }

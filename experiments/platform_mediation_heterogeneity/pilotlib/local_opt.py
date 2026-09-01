"""Policy-independent local task-selection diagnostic.

``queue_order_completion`` is what the canonical runtime completes when it executes
an agent's eight tasks in generated order under the installed contract.
``locally_optimized_completion`` is a counterfactual measure of what a locally
rational agent could complete under the *same* installed contract if it were free
to choose which subset of its own tasks to run.

For an agent's installed resource bundle and its eight task mandatory footprints,
enumerate all 2**8 = 256 subsets. A subset is feasible when the summed mandatory
resource footprint of its tasks does not exceed the installed bundle on any
resource. Among feasible subsets, select deterministically by:

1. maximize the number of completed mandatory tasks;
2. among equal-count subsets, maximize summed base task quality;
3. among remaining ties, minimize total mandatory resource consumption
   (summed over all resources);
4. resolve any final tie by the lexicographically smallest tuple of task indices.

This is a measurement, not a new allocation policy. It is computed identically for
every allocation policy from that policy's installed bundle.
"""


def build_subset_table(task_footprints, qualities, resources):
    """Precompute (count, quality, total_consumption, agg_vector, index_tuple) for
    all 256 subsets of an agent's tasks. Reusable across policies because the tasks
    (and hence footprints) are identical across policies within a scenario."""
    n = len(task_footprints)
    m = len(resources)
    fps = [[int(fp.get(r, 0)) for r in resources] for fp in task_footprints]
    table = []
    for mask in range(1 << n):
        agg = [0] * m
        cnt = 0
        qual = 0.0
        idxs = []
        for i in range(n):
            if (mask >> i) & 1:
                row = fps[i]
                for j in range(m):
                    agg[j] += row[j]
                cnt += 1
                qual += qualities[i]
                idxs.append(i)
        table.append((cnt, qual, sum(agg), tuple(agg), tuple(idxs)))
    return table


def select_from_table(table, alloc, resources):
    """Return (count, index_tuple, total_consumption, quality) of the selected
    feasible subset under the installed ``alloc`` bundle."""
    cap = [int(alloc.get(r, 0)) for r in resources]
    m = len(resources)
    best_key = None
    best = None
    for cnt, qual, total, agg, idxs in table:
        feasible = True
        for j in range(m):
            if agg[j] > cap[j]:
                feasible = False
                break
        if not feasible:
            continue
        key = (cnt, qual, -total)
        if best_key is None or key > best_key or (key == best_key and idxs < best[1]):
            best_key = key
            best = (cnt, idxs, total, qual)
    if best is None:                       # the empty subset is always feasible
        best = (0, tuple(), 0, 0.0)
    return best


def locally_optimized_completion(alloc, task_footprints, qualities, resources):
    """Convenience single-call form used by the tests. Returns a dict with the
    selected subset's completion fraction, task count, indices, consumption, and
    summed base quality."""
    table = build_subset_table(task_footprints, qualities, resources)
    cnt, idxs, total, qual = select_from_table(table, alloc, resources)
    n = len(task_footprints)
    return {
        "count": cnt,
        "completion": (cnt / n) if n else 0.0,
        "selected_indices": list(idxs),
        "total_consumption": total,
        "quality": qual,
    }

"""Resource floor (lower-bound) regimes for the pilot.

A floor regime sets each agent's per-resource lower bound ``min[i][j]``, which is
supplied identically to every policy through the canonical runtime. All policies
(equal, DRF, decomposed, and the four joint families) honour these bounds via the
solver constraints and the capacity-preserving rounding, so a floor regime is a
property of the scenario rather than of any single allocator.

Regimes
-------
* ``zero``               -- no lower bound on any resource.
* ``unit``               -- one unit on every resource an agent actually uses
                            (reproduces the current canonical lower bound).
* ``proportional_<f>``   -- an aggregate guaranteed-minimum budget of at most
                            ``floor(f * capacity_j)`` units per resource,
                            apportioned across agents in proportion to their raw
                            mandatory demand for that resource.

Proportional apportionment is deterministic largest-remainder (Hamilton) with
per-agent upper caps: it respects each agent's upper bound, never gives a positive
floor to an agent with zero demand for the resource, and never lets the total
lower bound for a resource exceed its budget (hence never exceed capacity).
"""
import math


def parse_regime(name):
    """Return ('zero'|'unit'|'proportional', fraction_or_None)."""
    if name == "zero":
        return "zero", None
    if name == "unit":
        return "unit", None
    if name.startswith("proportional_"):
        return "proportional", float(name.split("_", 1)[1])
    raise ValueError("unknown floor regime: %r" % name)


def _largest_remainder(budget, weights, uppers):
    """Apportion an integer ``budget`` across positions in proportion to
    ``weights`` (Hamilton method) subject to per-position integer ``uppers``.

    Positions with zero weight receive zero. The returned integer vector sums to
    ``min(budget, sum(uppers over positive-weight positions))``. Ties in the
    remainder step are broken by larger weight, then lower index (deterministic).
    """
    n = len(weights)
    out = [0] * n
    total_w = sum(w for w in weights if w > 0)
    if budget <= 0 or total_w <= 0:
        return out

    quota = [(budget * weights[i] / total_w) if weights[i] > 0 else 0.0 for i in range(n)]
    for i in range(n):
        if weights[i] > 0:
            out[i] = min(int(math.floor(quota[i])), uppers[i])
    remaining = budget - sum(out)

    # Distribute the leftover units in priority order of fractional remainder
    # (then larger weight, then lower index). In the ordinary case each position
    # receives at most one extra unit; positions capped at their upper bound are
    # skipped and their share flows to the next in priority (largest-remainder
    # with caps). The cursor advances every step so a position is not re-selected
    # until the queue cycles, which only happens once caps have bound.
    order = sorted((i for i in range(n) if weights[i] > 0),
                   key=lambda i: (quota[i] - math.floor(quota[i]), weights[i], -i), reverse=True)
    if not order:
        return out
    cursor = 0
    stalled = 0
    while remaining > 0 and stalled < len(order):
        i = order[cursor % len(order)]
        cursor += 1
        if out[i] < uppers[i]:
            out[i] += 1
            remaining -= 1
            stalled = 0
        else:
            stalled += 1
    return out


def compute_floors(regime_name, resources, mandatory_demand, upper, capacities):
    """Return ``(floors, realized_fraction)``.

    ``mandatory_demand`` and ``upper`` are lists (per agent) of resource->int dicts;
    ``capacities`` is a resource->int dict. ``floors`` is a list (per agent) of
    resource->int dicts. ``realized_fraction`` maps each resource to the fraction
    of its capacity committed as a floor.
    """
    kind, frac = parse_regime(regime_name)
    n = len(mandatory_demand)
    floors = [{r: 0 for r in resources} for _ in range(n)]

    if kind == "zero":
        pass
    elif kind == "unit":
        for i in range(n):
            for r in resources:
                if mandatory_demand[i][r] > 0:
                    floors[i][r] = min(1, upper[i][r])
    elif kind == "proportional":
        for r in resources:
            cap = capacities[r]
            budget = int(math.floor(frac * cap))
            weights = [mandatory_demand[i][r] for i in range(n)]
            uppers = [upper[i][r] for i in range(n)]
            col = _largest_remainder(budget, weights, uppers)
            for i in range(n):
                floors[i][r] = col[i]
    else:
        raise ValueError("unhandled floor kind %r" % kind)

    realized_fraction = {}
    for r in resources:
        cap = capacities[r]
        total = sum(floors[i][r] for i in range(n))
        realized_fraction[r] = (total / cap) if cap > 0 else 0.0
        # Safety: the total floor for a resource may never exceed its capacity.
        if total > cap:
            raise AssertionError("floor budget %d exceeds capacity %d for %s" % (total, cap, r))
    return floors, realized_fraction

# Comparator audit

This audit distinguishes four resource-allocation concepts used in the architecture experiment and
establishes, before preregistration, that the independent bundle max-min mechanism is distinct from DRF
and that the separable Leontief relaxation collapses to equal quotas under the tested conditions. All
numbers below are from development-namespace scenarios and constructed examples; no confirmatory seed was
used. The four mechanisms are the resource-local independent bundle max-min, DRF, the separable weighted-
log Leontief relaxation, and equal quotas.

## 1. Equal quotas

Equal quotas split each resource's capacity equally across the agents that use it, subject to floors and
upper bounds. It ignores the declared demand magnitude entirely.

## 2. DRF (implemented rule)

DRF (dominant resource fairness) equalizes each agent's *dominant share* across its full demand vector. For
agent i it computes the dominant divisor d_i = max_r (demand_ir / Q_r) over the resources it demands, then
raises a common dominant share t, giving every agent the scaled bundle a_ir = (t / d_i) * demand_ir until a
resource capacity binds or an agent reaches its upper bound, at which point that agent is frozen and the
remainder continues (the implemented water-filling in the Java harness). The dominant resource couples all
of an agent's resources: the same scalar t/d_i multiplies every component of demand_i, so the allocation on
one resource depends on the agent's demand on its *other* resources through d_i.

## 3. Independent bundle max-min (the tested uncoordinated resource-local mechanism)

Each resource owner sees only, for its one resource, each agent's declared fixed-proportion Leontief
coefficient a_ir, weight w_i, floor and upper bound. It runs weighted progressive filling of local bundle
progress x_ir / a_ir: x_ir(theta) = clip(theta * w_i * a_ir, floor_ir, upper_ir), theta raised until the
column fills capacity or all agents saturate. No owner sees another resource's allocation, residual, price
or the cross-resource bundle, and there is no cross-resource reconciliation. It uses the *same*
fixed-proportion declaration the joint mechanism receives, so the complementarity magnitude a_ir enters
each resource's fill, but the resources are never coupled. This is why it differs from DRF: DRF ties the
resources together through the dominant share, while independent bundle max-min fills each resource on its
own local progress. It is the strongest tested uncoordinated resource-local mechanism, not a universally
strongest mechanism.

### 3.1 Difference from DRF (empirical)

Over 120 randomized development scenarios (Dirichlet(0.1), both contention levels), the two mechanisms'
installed integer allocations differed in 120 of 120 (100.0%). Normalized allocation L1 distance: mean 0.1935,
max 0.3081; maximum L-infinity distance 71 units. Queue-order completion differed by mean 1.9917 tasks per
48-task run (range -4.000 to 7.000). The mechanisms are therefore distinct on both allocation and outcome.

### 3.2 Three constructed examples

* **mirror_heterogeneous_symmetric_capacity** (capacity (COM=6, MEM=6)):
  * agent 0 demand (COM=10, MEM=2) -> DRF (COM=5, MEM=1), max-min (COM=5, MEM=1)
  * agent 1 demand (COM=2, MEM=10) -> DRF (COM=1, MEM=5), max-min (COM=1, MEM=5)
  * differ: False

* **heavy_plus_balanced** (capacity (COM=9, MEM=6)):
  * agent 0 demand (COM=9, MEM=9) -> DRF (COM=4, MEM=3), max-min (COM=3, MEM=5)
  * agent 1 demand (COM=9, MEM=1) -> DRF (COM=5, MEM=1), max-min (COM=6, MEM=1)
  * differ: True

* **three_specialists** (capacity (COM=6, MEM=6, API=6)):
  * agent 0 demand (COM=8, MEM=1, API=1) -> DRF (COM=4, MEM=1, API=1), max-min (COM=4, MEM=1, API=1)
  * agent 1 demand (COM=1, MEM=8, API=1) -> DRF (COM=1, MEM=4, API=1), max-min (COM=1, MEM=4, API=1)
  * agent 2 demand (COM=1, MEM=1, API=8) -> DRF (COM=1, MEM=1, API=4), max-min (COM=1, MEM=1, API=4)
  * differ: False

The first (perfectly mirror-symmetric) case is one where the two mechanisms coincide; the second and third
show them diverging, because DRF's dominant-share coupling redistributes across resources while the
independent fill does not. DRF's dominant-share coupling is therefore not the same as separate local
progress filling.

## 4. Separable weighted-log Leontief relaxation (structural control)

The separable relaxation drops the cross-resource utility consensus from the weighted-log Leontief
objective, leaving each resource owner to maximize sum_i w_i log(x_ir) over the agents that require the
resource. Substituting u_ir = x_ir / a_ir gives sum_i w_i log(x_ir) - sum_i w_i log(a_ir); the second term
is constant in x, so the magnitude a_ir cancels and the owner allocates in proportion to w_i alone. Under
equal weights and inactive special bounds this is exactly an equal split among the participating agents,
i.e. equal quotas.

Constructed confirmation (2 agents, requirements COMPUTE/MEMORY = 0.7/0.3 and 0.3/0.7, capacity 10/10):
* equal weights, slack bounds -> [{'COMPUTE': 5, 'MEMORY': 5}, {'COMPUTE': 5, 'MEMORY': 5}] == equal quotas: True
* unequal weights (3, 1) -> [{'COMPUTE': 8, 'MEMORY': 8}, {'COMPUTE': 2, 'MEMORY': 2}] == equal quotas: False (collapse broken: weight-proportional)
* active upper bound (agent 0 COMPUTE <= 2) -> [{'COMPUTE': 2, 'MEMORY': 5}, {'COMPUTE': 8, 'MEMORY': 5}] == equal quotas: False (collapse broken on the bounded resource)

Empirically, over the 120 randomized development scenarios the relaxation equalled equal quotas in 120 (100.0%)
and equalled DRF in 0 (0.0%). The relaxation is retained as a structural architecture control that shows
what happens when cross-resource utility consensus is dropped from weighted-log Leontief; it is not the
primary independent comparator, because it discards the complementarity magnitude that independent bundle
max-min keeps.

## 5. Conclusion

Independent bundle max-min is mathematically distinct from DRF (resource-local progressive filling of
bundle progress versus dominant-share coupling) and empirically distinct on constructed and randomized
development cases. It is therefore eligible as the primary architecture comparator. The separable Leontief
relaxation collapses to equal quotas under the tested equal-weight, exact-information conditions and is
retained only as a structural control.


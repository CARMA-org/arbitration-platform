"""Task archetypes and service resource footprints.

The service resource vectors and base latencies mirror
src/main/java/org/carma/arbitration/model/ServiceType.java. They are needed on
the Python side only to size capacities and derive declarations; the actual
charges are produced by the Java runtime at execution time.
"""

RESOURCES = ["COMPUTE", "MEMORY", "API_CREDITS", "DATASET"]

SERVICE_FOOTPRINT = {
    "KNOWLEDGE_RETRIEVAL": {"COMPUTE": 4, "MEMORY": 6, "DATASET": 8},
    "REASONING": {"COMPUTE": 20, "MEMORY": 15, "API_CREDITS": 12},
    "TEXT_GENERATION": {"COMPUTE": 10, "MEMORY": 8, "API_CREDITS": 5},
    "TEXT_SUMMARIZATION": {"COMPUTE": 8, "MEMORY": 6, "API_CREDITS": 4},
    "CODE_ANALYSIS": {"COMPUTE": 10, "MEMORY": 8, "API_CREDITS": 6},
    "CODE_GENERATION": {"COMPUTE": 12, "MEMORY": 10, "API_CREDITS": 8},
    "OCR": {"COMPUTE": 8, "MEMORY": 6, "API_CREDITS": 3},
    "DATA_EXTRACTION": {"COMPUTE": 6, "MEMORY": 4, "API_CREDITS": 3},
    "TEXT_CLASSIFICATION": {"COMPUTE": 4, "MEMORY": 3, "API_CREDITS": 1},
    "VECTOR_SEARCH": {"COMPUTE": 3, "MEMORY": 10, "DATASET": 5},
}

SERVICE_LATENCY = {
    "KNOWLEDGE_RETRIEVAL": 50, "REASONING": 400, "TEXT_GENERATION": 100,
    "TEXT_SUMMARIZATION": 150, "CODE_ANALYSIS": 120, "CODE_GENERATION": 150,
    "OCR": 100, "DATA_EXTRACTION": 80, "TEXT_CLASSIFICATION": 30, "VECTOR_SEARCH": 20,
}

# Each archetype: mandatory + optional service steps, an externally defined base
# quality and refinement bonus, and an SLO latency budget. The SLO budget for
# non-deadline archetypes covers the mandatory steps but not the optional
# refinement, so completing a refinement trades SLO for quality. Monitoring is a
# deadline archetype whose SLO budget just covers its two mandatory steps.
ARCHETYPES = {
    "research": {
        "mandatory": ["KNOWLEDGE_RETRIEVAL", "REASONING", "TEXT_GENERATION"],
        "optional": ["TEXT_SUMMARIZATION"],
        "base_quality": 0.80, "refinement": 0.20, "slo_ms": 600,
    },
    "code_review": {
        "mandatory": ["CODE_ANALYSIS"],
        "optional": ["CODE_GENERATION", "TEXT_GENERATION"],
        "base_quality": 0.75, "refinement": 0.25, "slo_ms": 140,
    },
    "doc_processing": {
        "mandatory": ["OCR", "DATA_EXTRACTION", "TEXT_SUMMARIZATION"],
        "optional": ["TEXT_GENERATION"],
        "base_quality": 0.70, "refinement": 0.30, "slo_ms": 380,
    },
    "monitoring": {
        "mandatory": ["KNOWLEDGE_RETRIEVAL", "TEXT_CLASSIFICATION"],
        "optional": [],
        "base_quality": 0.60, "refinement": 0.0, "slo_ms": 100,
    },
}

ALL_SERVICES = sorted(SERVICE_FOOTPRINT.keys())


def archetype_footprint(name, include_optional=True):
    """Resource footprint of one task of the archetype (mandatory + optional)."""
    arc = ARCHETYPES[name]
    steps = list(arc["mandatory"]) + (list(arc["optional"]) if include_optional else [])
    fp = {r: 0 for r in RESOURCES}
    for s in steps:
        for r, v in SERVICE_FOOTPRINT[s].items():
            fp[r] += v
    return fp

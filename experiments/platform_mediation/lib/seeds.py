"""Deterministic hashed seeds with disjoint calibration/test splits."""
import hashlib


def derive_seed(*parts):
    key = "|".join(str(p) for p in parts)
    digest = hashlib.sha256(key.encode()).hexdigest()
    return int(digest[:16], 16) % (2 ** 32)


def seed_split(base_label, n_calibration, n_test):
    calib = [derive_seed(base_label, "calibration", i) for i in range(n_calibration)]
    test = [derive_seed(base_label, "test", i) for i in range(n_test)]
    assert not (set(calib) & set(test)), "calibration/test seed collision"
    return calib, test

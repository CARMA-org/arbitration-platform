import hashlib
import numpy as np


def derive_seed(*parts):
    key = "|".join(str(p) for p in parts)
    digest = hashlib.sha256(key.encode()).hexdigest()
    return int(digest[:16], 16) % (2**32)


def rng(*parts):
    return np.random.default_rng(derive_seed(*parts))


def seed_split(base_label, n_train, n_test):
    """Disjoint deterministic train/test seed lists for a labelled cell."""
    train = [derive_seed(base_label, "train", i) for i in range(n_train)]
    test = [derive_seed(base_label, "test", i) for i in range(n_test)]
    assert not (set(train) & set(test)), "train/test seed collision"
    return train, test

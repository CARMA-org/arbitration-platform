"""Resumable, deterministic raw-data store shared by the architecture and drift
drivers.

Rows are appended to per-table partial caches as units of work complete, and a unit's
key is recorded only after its rows are written. On restart, completed units are
skipped, so an interrupted run resumes without recomputing finished work and without
replacing it. ``finalize`` deduplicates by each table's unique key (a defensive guard
against a crash between writing rows and recording the unit) and rewrites every table in
a single canonical sort order, so the committed raw data is byte-identical regardless of
how many times the run was resumed or in what block order the units executed.
"""
import csv
import json
import os


class RawStore:
    def __init__(self, out_dir, tables, unit_keys):
        """``tables`` maps table name -> field list. ``unit_keys`` maps table name -> the
        tuple of field names that uniquely identify a row (for dedup and canonical sort)."""
        self.out_dir = out_dir
        self.tables = tables
        self.unit_keys = unit_keys
        self.partial = os.path.join(out_dir, "_partial")
        os.makedirs(self.partial, exist_ok=True)
        self.done = self._load_done()

    def _ppath(self, name):
        return os.path.join(self.partial, name + ".csv")

    def _load_done(self):
        p = os.path.join(self.partial, "progress.json")
        if os.path.exists(p):
            return set(tuple(x) for x in json.load(open(p)))
        return set()

    def _save_done(self):
        tmp = os.path.join(self.partial, "progress.json.tmp")
        with open(tmp, "w") as f:
            json.dump(sorted(list(u) for u in self.done), f)
        os.replace(tmp, os.path.join(self.partial, "progress.json"))

    def is_done(self, unit):
        return tuple(unit) in self.done

    def append(self, unit, rows_by_table):
        for name, rows in rows_by_table.items():
            if not rows:
                continue
            path = self._ppath(name)
            exists = os.path.exists(path)
            with open(path, "a", newline="") as f:
                w = csv.DictWriter(f, fieldnames=self.tables[name], lineterminator="\n")
                if not exists:
                    w.writeheader()
                w.writerows(rows)
                f.flush()
                os.fsync(f.fileno())
        self.done.add(tuple(unit))
        self._save_done()

    def finalize(self):
        raw = os.path.join(self.out_dir, "raw")
        os.makedirs(raw, exist_ok=True)
        counts = {}
        for name, fields in self.tables.items():
            rows = []
            if os.path.exists(self._ppath(name)):
                with open(self._ppath(name)) as f:
                    rows = list(csv.DictReader(f))
            keyf = self.unit_keys[name]
            seen = set()
            deduped = []
            for r in rows:
                k = tuple(r[f] for f in keyf)
                if k in seen:
                    continue
                seen.add(k)
                deduped.append(r)
            deduped.sort(key=lambda r: _sortable(tuple(r[f] for f in keyf)))
            with open(os.path.join(raw, name + ".csv"), "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
                w.writeheader()
                w.writerows(deduped)
            counts[name] = len(deduped)
        return counts


def _sortable(key):
    out = []
    for x in key:
        try:
            out.append((0, float(x)))
        except (TypeError, ValueError):
            out.append((1, str(x)))
    return tuple(out)

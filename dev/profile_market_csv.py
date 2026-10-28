"""Aggregate-only Harvester CSV checks. Never emits sold listing bodies or modifies input."""
from __future__ import annotations
import argparse
import collections
import csv
import datetime
import decimal
import hashlib
import json
import re
import unicodedata
from pathlib import Path


def profile(path: Path) -> dict:
    rows = 0
    currencies, sites, missing, invalid = (collections.Counter() for _ in range(4))
    identities, local_ids = collections.Counter(), collections.Counter()
    observed_payloads = {}
    conflicting = set()
    first, last = None, None
    generic = 0
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        columns = reader.fieldnames
        required = {"title", "site", "item_id", "date_sold", "sale_price", "shipping_price", "currency", "url"}
        if not columns or not required <= set(columns):
            raise ValueError("Unsupported Harvester columns")
        for row in reader:
            rows += 1
            currencies[row["currency"]] += 1
            sites[row["site"]] += 1
            for key, value in row.items():
                if not value:
                    missing[key] += 1
            if "id" in row:
                local_ids[row["id"]] += 1
            identity = (row["site"], row["item_id"], row["date_sold"])
            identities[identity] += 1
            content = json.dumps({k: v for k, v in row.items() if k != "id"}, sort_keys=True).encode()
            digest = hashlib.sha256(content).hexdigest()
            prior = observed_payloads.setdefault(identity, digest)
            if prior != digest:
                conflicting.add(identity)
            for field in ("sale_price", "shipping_price"):
                try:
                    value = decimal.Decimal(row[field])
                    if not value.is_finite() or not 0 <= value <= 1_000_000:
                        raise ValueError()
                except (ValueError, decimal.InvalidOperation):
                    invalid[field] += 1
            try:
                value = datetime.datetime.fromisoformat(row["date_sold"].replace("Z", "+00:00"))
                first = min(first, value) if first else value
                last = max(last, value) if last else value
            except ValueError:
                invalid["date_sold"] += 1
            title = "".join(c for c in unicodedata.normalize("NFKD", row["title"]) if not unicodedata.combining(c))
            words = re.sub(r"[^\w]+", " ", title.casefold()).split()
            generic += not any(w not in {"vhs", "tape", "movie", "video", "cassette"} for w in words)
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(262144):
            digest.update(block)
    return {"file": path.name, "bytes": path.stat().st_size, "sha256": digest.hexdigest(),
            "rows": rows, "columns": columns, "currencies": dict(currencies), "sites": dict(sites),
            "missing": dict(missing), "invalid": dict(invalid),
            "duplicate_local_ids": sum(v - 1 for v in local_ids.values()),
            "duplicate_source_sale_rows": sum(v - 1 for v in identities.values()),
            "conflicting_source_sale_keys": len(conflicting), "generic_format_only_titles": generic,
            "first_sale": first.isoformat() if first else None, "last_sale": last.isoformat() if last else None}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = json.dumps(profile(args.csv), indent=2)
    if args.output:
        args.output.write_text(text + "\n")
    print(text)

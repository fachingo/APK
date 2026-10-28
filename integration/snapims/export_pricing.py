"""Export a consistent pricing SQLite snapshot without changing the source database."""
from __future__ import annotations
import argparse
import pathlib
import sqlite3


def export(source: pathlib.Path, output: pathlib.Path) -> None:
    source = source.resolve(strict=True)
    output = output.resolve()
    if source == output or output.exists():
        raise ValueError("Choose a new output file; existing files are never replaced")
    try:
        with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True) as read:
            read.execute("PRAGMA query_only=ON")
            read.execute("SELECT collection_id,queried_title FROM pricing_evidence LIMIT 1")
            read.execute("SELECT sold_price_cad,shipping_price_cad FROM pricing_evidence_listings LIMIT 1")
            with sqlite3.connect(output) as write:
                read.backup(write)
                if write.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                    raise ValueError("Export failed integrity verification")
    except Exception:
        output.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    export(args.source, args.output)
    print(f"Saved consistent pricing export: {args.output}")

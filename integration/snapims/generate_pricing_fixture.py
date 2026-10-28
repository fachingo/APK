"""Explicit synthetic test vectors evaluated by the unmodified desktop pricing code."""
from pathlib import Path
import json
from decimal import Decimal
from snapims.pricing.normalization import listing_matches
from snapims.pricing.statistics import calculate_statistics
from snapims.pricing.models import SoldListing

cases = [
    ("The Matrix", "The Matrix VHS 1999 Sealed"),
    ("The Matrix", "The Matrix Reloaded VHS"),
    ("No", "No VHS Sealed"), ("New", "New Listing: New VHS"),
    ("Good", "Good movie VHS Canadian edition"),
    ("Alien", "Alien VHS Lot 2"), ("Alien", "Aliens VHS"),
    ("Alien", "Alien VHS 1979 tested"), ("Batman", "Batman Returns VHS"),
    ("Straße", "STRASSE VHS"), ("Amélie", "Amelie VHS"),
]
amounts = [[("10.005", "2.00")], [("10", "2"), ("20", "4"), ("30", "6")],
           [("0.01", "0.00"), ("0.02", "0.00")], [], [("19.99", "7.5"), ("5", "3.5")]]
statistics = []
for values in amounts:
    result = calculate_statistics([SoldListing(Decimal(a), Decimal(b), "Fixture", "https://example.invalid", "2026-01-01") for a,b in values])
    statistics.append({"values": values, "result": None if result is None else {
        "averageCents": int(result.average*100), "medianCents": int(result.median*100),
        "lowestCents": int(result.lowest*100), "highestCents": int(result.highest*100), "count": result.count}})
root = Path(__file__).resolve().parents[2]
(root / "core/src/test/resources/desktop_pricing.json").write_text(json.dumps({"source": "Unmodified SnapIMS pricing functions; synthetic unit-test vectors only", "matches": [{"requested": a,"listing": b,"matches": listing_matches(a,b)} for a,b in cases],"statistics":statistics}, indent=2)+"\n")

# Real source-created schema and evidence rows; all content is explicitly synthetic.
import tempfile
import hashlib
import sqlite3
from snapims.pricing.database import PricingDatabase
from snapims.pricing.models import MovieAnalysis, AnalysisStatus
from integration.snapims.export_pricing import export
with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / "pricing.sqlite"
    database = PricingDatabase(source)
    listings = [SoldListing(Decimal("10"),Decimal("2"),"Alien VHS","https://example.invalid/fixture","2026-01-01"),
                SoldListing(Decimal("20"),Decimal("4"),"Alien VHS Sealed","https://example.invalid/fixture2","2026-01-02")]
    database.save_analysis(MovieAnalysis(movie="Alien",search_url="https://example.invalid/sold",status=AnalysisStatus.COMPLETE,listings=listings,statistics=calculate_statistics(listings)))
    with sqlite3.connect(source) as connection:
        connection.execute("UPDATE pricing_evidence SET collection_id='synthetic-desktop-fixture',collected_at='2026-01-01T00:00:00+00:00',expires_at='2036-01-01T00:00:00+00:00',analysis_date='2026-01-01',created_at='2026-01-01T00:00:00+00:00'")
        connection.commit()
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    target = root / "app/src/test/resources/desktop-pricing-fixture.sqlite"
    target.unlink(missing_ok=True)
    export(source, target)
    assert before == hashlib.sha256(source.read_bytes()).hexdigest(), "Exporter changed source"
    print("Synthetic source-created pricing fixture exported; source bytes unchanged.")

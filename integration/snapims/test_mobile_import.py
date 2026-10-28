from __future__ import annotations

import hashlib
from io import BytesIO
import json
from pathlib import Path
from uuid import uuid4
import zipfile

from PIL import Image
import pytest

from snapims import db
from snapims.config import DataPaths
from snapims.mobile_import import MobileImportError, import_mobile
from snapims.processor import ImportInterrupted


def uid():
    return str(uuid4())


def fixture(count=2, photos_per_group=3):
    content = BytesIO()
    Image.new("RGB", (320, 240), (40, 90, 120)).save(content, "JPEG")
    image = content.getvalue()
    blobs = {}
    groups = []
    for number in range(1, count + 1):
        photos = []
        for order in range(1, photos_per_group + 1):
            pid = uid()
            filename = f"{pid}.jpg"
            blobs[filename] = image
            photos.append(
                dict(
                    id=pid,
                    order=order,
                    role={1: "FRONT", 2: "BACK"}.get(order, "ADDITIONAL"),
                    captured_at="2026-10-27T12:00:00-06:00",
                    filename=filename,
                    bytes=len(image),
                    sha256=hashlib.sha256(image).hexdigest(),
                )
            )
        groups.append(
            dict(
                id=uid(),
                number=number,
                captured_at="2026-10-27T12:00:00-06:00",
                location="A1",
                rare=number == 1,
                review=False,
                note="",
                barcode=dict(
                    status="FOUND",
                    selected="036000291452",
                    candidates=[
                        dict(
                            raw="036000291452",
                            format="UPC_A",
                            normalized="00036000291452",
                            valid=True,
                            provider="fixture",
                        )
                    ],
                ),
                photos=photos,
            )
        )
    return dict(
        format="snapims.mobile",
        version=1,
        archive_id=uid(),
        part=1,
        session=dict(id=uid(), name="Mobile acceptance", created_at="2026-10-27T12:00:00-06:00"),
        groups=groups,
        events=[],
    ), blobs


def write_archive(path, manifest, blobs, extra=None):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        for name, data in blobs.items():
            archive.writestr(name, data)
        if extra:
            archive.writestr(*extra)
    return path


def test_import_preserves_groups_order_originals_roles_and_duplicate_upcs(tmp_path):
    manifest, blobs = fixture()
    paths = DataPaths.from_root(tmp_path / "data")
    archive = write_archive(tmp_path / "part.zip", manifest, blobs)
    receipt = import_mobile(archive, paths=paths)
    assert len(receipt["groups"]) == 2
    assert len({g["item_id"] for g in receipt["groups"]}) == 2
    items = db.list_items(paths.db_file, batch_id=receipt["groups"][0]["batch_id"])
    assert [i["barcode"] for i in items] == ["036000291452"] * 2
    assert bool(items[0]["rare"])
    with db.connect(paths.db_file) as connection:
        photos = connection.execute("SELECT * FROM photos ORDER BY stream_index").fetchall()
        assert [p["image_role"] for p in photos] == ["front", "back", "additional"] * 2
        assert {json.loads(p["capture_metadata_json"])["capture_group_id"] for p in photos} == {
            g["id"] for g in manifest["groups"]
        }
        for photo in photos:
            assert Path(photo["original_copy_path"]).read_bytes() == blobs[photo["original_name"]]
    again = import_mobile(archive, paths=paths)
    assert again["duplicate"] and again["groups"] == receipt["groups"]
    assert db.database_summary(paths.db_file)["items"] == 2


@pytest.mark.parametrize(
    "change",
    [
        "checksum",
        "path",
        "order",
        "version",
        "timezone",
        "role",
        "size",
        "duplicate_group",
        "duplicate_photo",
        "extra",
        "malformed",
        "symlink",
    ],
)
def test_rejects_invalid_before_inventory_is_created(tmp_path, change):
    manifest, blobs = fixture()
    p = manifest["groups"][0]["photos"][0]
    extra = None
    if change == "checksum":
        p["sha256"] = "0" * 64
    if change == "path":
        blobs["../escape.jpg"] = blobs.pop(p["filename"])
        p["filename"] = "../escape.jpg"
    if change == "order":
        p["order"] = 9
    if change == "version":
        manifest["version"] = 9
    if change == "timezone":
        p["captured_at"] = "2026-10-27T12:00:00"
    if change == "role":
        p["role"] = "INVENTORY"
    if change == "size":
        p["bytes"] += 1
    if change == "duplicate_group":
        manifest["groups"][1]["id"] = manifest["groups"][0]["id"]
    if change == "duplicate_photo":
        manifest["groups"][1]["photos"][0]["id"] = p["id"]
    if change == "extra":
        extra = ("surprise.txt", "unexpected")
    if change == "malformed":
        manifest["groups"] = [None]
    if change == "symlink":
        link = zipfile.ZipInfo("link.jpg")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        extra = (link, "/etc/passwd")
    archive = write_archive(tmp_path / "bad.zip", manifest, blobs, extra)
    paths = DataPaths.from_root(tmp_path / "data")
    with pytest.raises(MobileImportError):
        import_mobile(archive, paths=paths)
    assert not paths.db_file.exists()


def test_no_barcode_incomplete_and_fallback_location(tmp_path):
    manifest, blobs = fixture(1, 1)
    group = manifest["groups"][0]
    group["location"] = ""
    group["review"] = True
    group["barcode"] = dict(status="NO_BARCODE", candidates=[], selected=None)
    archive = write_archive(tmp_path / "part.zip", manifest, blobs)
    paths = DataPaths.from_root(tmp_path / "data")
    with pytest.raises(MobileImportError, match="Unlocated"):
        import_mobile(archive, paths=paths)
    receipt = import_mobile(archive, paths=paths, location="Q1")
    item = db.list_items(paths.db_file, batch_id=receipt["groups"][0]["batch_id"])[0]
    assert item["barcode"] == "" and item["shelf"] == "Q1" and item["review"]


def test_repacked_archive_and_partial_overlap_are_idempotent(tmp_path):
    manifest, blobs = fixture()
    archive = write_archive(tmp_path / "part.zip", manifest, blobs)
    paths = DataPaths.from_root(tmp_path / "data")
    first = import_mobile(archive, paths=paths)
    manifest["archive_id"] = uid()
    manifest["part"] = 2
    second = import_mobile(write_archive(tmp_path / "repacked.zip", manifest, blobs), paths=paths)
    assert second["duplicate"] and second["groups"] == first["groups"]
    assert db.database_summary(paths.db_file)["items"] == 2


def test_conflicting_group_is_refused_without_overwriting(tmp_path):
    manifest, blobs = fixture()
    paths = DataPaths.from_root(tmp_path / "data")
    import_mobile(write_archive(tmp_path / "a.zip", manifest, blobs), paths=paths)
    manifest["archive_id"] = uid()
    manifest["groups"][0]["review"] = True
    with pytest.raises(MobileImportError, match="changed after import"):
        import_mobile(write_archive(tmp_path / "b.zip", manifest, blobs), paths=paths)
    assert db.database_summary(paths.db_file)["items"] == 2


@pytest.mark.parametrize(
    "phase",
    ["after_journal", "after_originals_rename", "after_media_rename", "after_database_commit"],
)
def test_resume_desktop_import_after_interruption(tmp_path, phase):
    manifest, blobs = fixture()
    paths = DataPaths.from_root(tmp_path / "data")
    archive = write_archive(tmp_path / "part.zip", manifest, blobs)
    with pytest.raises(ImportInterrupted):
        import_mobile(archive, paths=paths, interrupt_at=phase)
    receipt = import_mobile(archive, paths=paths)
    assert len(receipt["groups"]) == 2
    assert db.database_summary(paths.db_file)["items"] == 2
    assert import_mobile(archive, paths=paths)["duplicate"]


def test_synthetic_100_products_300_photographs(tmp_path):
    manifest, blobs = fixture(100, 3)
    paths = DataPaths.from_root(tmp_path / "data")
    archive = write_archive(tmp_path / "stress.zip", manifest, blobs)
    receipt = import_mobile(archive, paths=paths)
    assert len(receipt["groups"]) == 100
    assert db.database_summary(paths.db_file)["items"] == 100
    with db.connect(paths.db_file) as c:
        assert c.execute("SELECT count(*) FROM photos").fetchone()[0] == 300
    assert import_mobile(archive, paths=paths)["duplicate"]
    assert db.database_summary(paths.db_file)["items"] == 100

"""Validated manual mobile ZIP ingestion using the existing SnapIMS processor.

Run: python -m snapims.mobile_import batch.zip --data-dir /explicit/data/root
No network endpoint, recognition call, pricing operation or Shopify write is made.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import tempfile
from uuid import UUID
import zipfile

from PIL import Image

from snapims import db
from snapims.config import DataPaths
from snapims.interpreter import make_batch_id
from snapims.manifests import item_id
from snapims.models import BatchRecord, ItemRecord, PhotoRecord
from snapims.processor import process_batch

MAX_BYTES = 10_000_000_000
MAX_PHOTOS = 5000
MAX_MANIFEST = 5_000_000


class MobileImportError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MobileImportError(message)


def canonical(value: object) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode()


def identifier(value: object) -> str:
    require(isinstance(value, str), "Missing capture UUID")
    try:
        require(str(UUID(value)) == value, "Capture UUID must be canonical")
    except (ValueError, TypeError, AttributeError) as exc:
        raise MobileImportError("Invalid capture UUID") from exc
    return value


def timestamp(value: object) -> datetime:
    require(isinstance(value, str), "Missing timestamp")
    try:
        result = datetime.fromisoformat(value)
    except ValueError as exc:
        raise MobileImportError("Invalid timestamp") from exc
    require(
        result.tzinfo is not None and result.utcoffset() is not None, "Timestamp needs timezone"
    )
    return result


def load_manifest(archive: zipfile.ZipFile) -> dict:
    entries = archive.infolist()
    names = [e.filename for e in entries]
    require(len(entries) <= MAX_PHOTOS + 1, "Too many archive members")
    require(len(names) == len(set(names)), "Duplicate ZIP member")
    for entry in entries:
        require(
            re.fullmatch(r"[A-Za-z0-9_.-]+", entry.filename) is not None
            and entry.filename not in {".", ".."},
            "Unsafe archive path",
        )
        require(
            not entry.is_dir() and not stat.S_ISLNK(entry.external_attr >> 16),
            "Directories/symlinks not accepted",
        )
        require(not entry.flag_bits & 1, "Encrypted archives not accepted")
    require(sum(e.file_size for e in entries) <= MAX_BYTES, "Archive exceeds 10 GB safety bound")
    require("manifest.json" in names, "Missing manifest.json")
    require(archive.getinfo("manifest.json").file_size <= MAX_MANIFEST, "Manifest too large")
    try:

        def no_duplicates(pairs):
            result = {}
            for key, value in pairs:
                require(key not in result, "Duplicate JSON key")
                result[key] = value
            return result

        manifest = json.loads(
            archive.read("manifest.json"),
            object_pairs_hook=no_duplicates,
            parse_constant=lambda _: require(False, "Nonfinite JSON value"),
        )
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise MobileImportError("Invalid manifest JSON") from exc
    require(
        isinstance(manifest, dict)
        and manifest.get("format") == "snapims.mobile"
        and type(manifest.get("version")) is int
        and manifest["version"] == 1,
        "Unsupported mobile manifest format/version",
    )
    identifier(manifest.get("archive_id"))
    require(type(manifest.get("part")) is int and manifest["part"] > 0, "Invalid archive part")
    session = manifest.get("session")
    require(isinstance(session, dict), "Missing session")
    identifier(session.get("id"))
    timestamp(session.get("created_at"))
    require(
        isinstance(session.get("name"), str) and 0 < len(session["name"]) <= 80,
        "Invalid batch name",
    )
    groups = manifest.get("groups")
    require(isinstance(groups, list) and 0 < len(groups) <= MAX_PHOTOS, "Missing groups")
    seen_groups, seen_photos, seen_files, numbers = set(), set(), set(), set()
    for group in groups:
        require(isinstance(group, dict), "Invalid group")
        gid = identifier(group.get("id"))
        require(gid not in seen_groups, "Duplicate capture group")
        seen_groups.add(gid)
        number = group.get("number")
        require(
            type(number) is int and number > 0 and number not in numbers, "Invalid product number"
        )
        numbers.add(number)
        timestamp(group.get("captured_at"))
        require(
            isinstance(group.get("location"), str)
            and re.fullmatch(r"|Q1|[A-J](?:[1-9]|10)", group["location"]) is not None,
            "Invalid location",
        )
        require(
            type(group.get("rare")) is bool and type(group.get("review")) is bool, "Invalid flags"
        )
        require(
            isinstance(group.get("note", ""), str) and len(group.get("note", "")) <= 1000,
            "Invalid note",
        )
        barcode = group.get("barcode")
        require(
            isinstance(barcode, dict)
            and isinstance(barcode.get("status"), str)
            and isinstance(barcode.get("candidates"), list),
            "Invalid barcode provenance",
        )
        selected = barcode.get("selected")
        require(
            selected is None or isinstance(selected, str) and len(selected) <= 128,
            "Invalid selected barcode",
        )
        photos = group.get("photos")
        require(isinstance(photos, list) and photos, "Empty capture group")
        for index, photo in enumerate(photos, 1):
            require(isinstance(photo, dict), "Invalid photo")
            pid = identifier(photo.get("id"))
            require(pid not in seen_photos, "Photo occurs in multiple groups")
            seen_photos.add(pid)
            require(
                type(photo.get("order")) is int and photo["order"] == index,
                "Photos must have contiguous order",
            )
            require(photo.get("role") in {"FRONT", "BACK", "ADDITIONAL"}, "Invalid photo role")
            timestamp(photo.get("captured_at"))
            name = photo.get("filename")
            require(
                isinstance(name, str)
                and name.lower().endswith(".jpg")
                and name not in seen_files
                and name in names,
                "Missing/duplicate photo file",
            )
            seen_files.add(name)
            require(
                type(photo.get("bytes")) is int
                and photo["bytes"] > 0
                and photo["bytes"] == archive.getinfo(name).file_size,
                "Photo size mismatch",
            )
            require(
                isinstance(photo.get("sha256"), str)
                and re.fullmatch(r"[0-9a-f]{64}", photo["sha256"]) is not None,
                "Invalid photo SHA-256",
            )
    require(set(names) == seen_files | {"manifest.json"}, "Unmanifested archive member")
    require(len(seen_photos) <= MAX_PHOTOS, "Too many photos")
    return manifest


def validated_extract(archive_path: Path, destination: Path) -> dict:
    """Validate every byte/image before inventory initialization or mutation."""
    try:
        with zipfile.ZipFile(archive_path) as archive:
            manifest = load_manifest(archive)
            for group in manifest["groups"]:
                for photo in group["photos"]:
                    target = destination / photo["filename"]
                    digest = hashlib.sha256()
                    count = 0
                    with archive.open(photo["filename"]) as source, target.open("xb") as out:
                        while chunk := source.read(1024 * 1024):
                            count += len(chunk)
                            require(count <= photo["bytes"], "Expanded photo too large")
                            digest.update(chunk)
                            out.write(chunk)
                        out.flush()
                        os.fsync(out.fileno())
                    require(
                        count == photo["bytes"] and digest.hexdigest() == photo["sha256"],
                        "Photo checksum mismatch",
                    )
                    with Image.open(target) as image:
                        require(image.format == "JPEG", "Expected original JPEG image")
                        image.verify()
            return manifest
    except MobileImportError:
        raise
    except (
        zipfile.BadZipFile,
        OSError,
        KeyError,
        TypeError,
        ValueError,
        Image.DecompressionBombError,
    ) as exc:
        raise MobileImportError(f"Invalid mobile archive: {type(exc).__name__}") from exc


@contextmanager
def import_lock(root: Path):
    # Workstation is Linux. Lock is shared by every adapter invocation.
    import fcntl

    with (root / "import.lock").open("a+b") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield


def recover_mobile_media(paths: DataPaths, fingerprint: str) -> None:
    """Finish only the verified mobile originals-finalized rename boundary.

    SnapIMS deliberately quarantines mixed staging/final trees. The adapter can
    complete this known boundary after matching the original hashes to its journal.
    Other ambiguous arrangements are left untouched for operator inspection.
    """
    db.initialize(paths.db_file, paths=paths)
    with db.connect(paths.db_file) as connection:
        row = connection.execute(
            "SELECT * FROM import_journal WHERE source_fingerprint=?", (fingerprint,)
        ).fetchone()
    if not row:
        return
    payload = json.loads(row["manifest_json"])
    if payload.get("batch", {}).get("capture_source") != "ANDROID_MOBILE_V1":
        return
    staging_o, staging_p = Path(row["staging_originals"]), Path(row["staging_processed"])
    final_o, final_p = Path(row["final_originals"]), Path(row["final_processed"])
    if staging_o.exists() or final_p.exists() or not final_o.is_dir() or not staging_p.is_dir():
        return
    require(
        final_o.resolve() == (paths.originals / row["batch_id"]).resolve()
        and final_p.resolve() == (paths.processed / row["batch_id"]).resolve(),
        "Unexpected mobile recovery paths",
    )
    require(
        staging_p.resolve().parent == paths.processed.resolve()
        and staging_p.name.startswith(".staging-"),
        "Unexpected mobile staging path",
    )
    for artifact in payload["artifacts"]:
        original = Path(artifact["original_copy_path"])
        require(original.resolve().parent == final_o.resolve(), "Unexpected original recovery path")
        photo = payload["batch"]["photos"][str(artifact["stream_index"])]
        require(original.is_file(), "Missing mobile recovery original")
        with original.open("rb") as handle:
            require(
                hashlib.file_digest(handle, "sha256").hexdigest() == photo["sha256"],
                "Mobile recovery original checksum mismatch",
            )
        for field in ("processed_path", "thumbnail_path", "recognition_path"):
            if artifact.get(field):
                relative = Path(artifact[field]).relative_to(final_p)
                candidate = staging_p / relative
                require(
                    candidate.resolve().is_relative_to(staging_p.resolve()) and candidate.is_file(),
                    "Missing mobile recovery derivative",
                )
                with Image.open(candidate) as image:
                    image.verify()
    require((staging_p / "batch_manifest.json").is_file(), "Missing mobile recovery manifest")
    os.replace(staging_p, final_p)


def import_mobile(
    archive_path: Path, *, paths: DataPaths, location: str = "", interrupt_at: str = ""
) -> dict:
    require(
        not location or re.fullmatch(r"Q1|[A-J](?:[1-9]|10)", location) is not None,
        "Invalid fallback location",
    )
    # Temporary extraction is not in inventory; invalid archives never initialize it.
    with tempfile.TemporaryDirectory(prefix="snapims-mobile-") as temporary:
        source = Path(temporary)
        manifest = validated_extract(archive_path, source)
        for group in manifest["groups"]:
            require(
                bool(group["location"] or location),
                "Unlocated product: supply --location A1 (or assign a mobile location)",
            )
        root = paths.root / "mobile-imports"
        root.mkdir(parents=True, exist_ok=True)
        with import_lock(root), sqlite3.connect(root / "receipts.sqlite3") as receipts:
            receipts.row_factory = sqlite3.Row
            receipts.execute("PRAGMA synchronous=FULL")
            receipts.executescript("""
                CREATE TABLE IF NOT EXISTS groups(capture_id TEXT PRIMARY KEY, digest TEXT NOT NULL, batch_id TEXT NOT NULL, item_id TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS archives(archive_id TEXT PRIMARY KEY, digest TEXT NOT NULL, receipt TEXT NOT NULL);
            """)
            archive_id = manifest["archive_id"]
            # Only semantic capture data, not volatile JSON key order/ZIP compression.
            digest = hashlib.sha256(
                canonical({"manifest": manifest, "fallback_location": location})
            ).hexdigest()
            prior = receipts.execute(
                "SELECT * FROM archives WHERE archive_id=?", (archive_id,)
            ).fetchone()
            if prior:
                require(
                    prior["digest"] == digest,
                    "Archive ID was previously imported with different contents/location",
                )
                receipt = json.loads(prior["receipt"])
                receipt["duplicate"] = True
                return receipt
            new_groups, mappings = [], []
            for group in manifest["groups"]:
                group["effective_location"] = group["location"] or location
                signature = hashlib.sha256(
                    canonical({"session_id": manifest["session"]["id"], "group": group})
                ).hexdigest()
                prior_group = receipts.execute(
                    "SELECT * FROM groups WHERE capture_id=?", (group["id"],)
                ).fetchone()
                if prior_group:
                    require(
                        prior_group["digest"] == signature,
                        "Capture group changed after import; refused to create duplicate inventory",
                    )
                    mappings.append(
                        {
                            "capture_group_id": group["id"],
                            "batch_id": prior_group["batch_id"],
                            "item_id": prior_group["item_id"],
                        }
                    )
                else:
                    new_groups.append((group, signature))
            if new_groups:
                now = datetime.now().astimezone()
                fingerprint = hashlib.sha256(
                    b"mobile-v1\0" + canonical([(g["id"], d) for g, d in new_groups])
                ).hexdigest()
                recover_mobile_media(paths, fingerprint)
                batch = BatchRecord(
                    batch_id=make_batch_id(now, manifest["session"]["name"] + "-M-" + archive_id),
                    source_folder=source,
                    created_at=now,
                    source_fingerprint=fingerprint,
                    capture_source="ANDROID_MOBILE_V1",
                    started=True,
                    ended=True,
                )
                stream = 0
                for sequence, (group, _) in enumerate(new_groups, 1):
                    item = ItemRecord(
                        sequence=sequence,
                        shelf=group["effective_location"],
                        rare=group["rare"],
                        review=group["review"],
                    )
                    for photo in group["photos"]:
                        stream += 1
                        record = PhotoRecord(
                            path=source / photo["filename"],
                            original_name=photo["filename"],
                            captured_at=timestamp(photo["captured_at"]),
                            timestamp_source="MOBILE_MANIFEST",
                            stream_index=stream,
                            sha256=photo["sha256"],
                        )
                        item.photos.append(record)
                        batch.source_photos.append(record)
                    batch.items.append(item)
                # Reuse interrupted output/desktop fingerprint rather than allocate another batch.
                result = process_batch(
                    source,
                    paths=paths,
                    prepared_batch=batch,
                    batch_name=manifest["session"]["name"],
                    interrupt_at=interrupt_at,
                )
                with db.transaction(paths.db_file) as connection:
                    connection.execute(
                        "UPDATE batches SET display_name=?,source_folder_name=? WHERE batch_id=?",
                        (manifest["session"]["name"], manifest["session"]["name"], result.batch_id),
                    )
                    stream = 0
                    for sequence, (group, signature) in enumerate(new_groups, 1):
                        iid = item_id(result.batch_id, group["effective_location"], sequence)
                        # Only annotate freshly imported items, never overwrite later operator edits on recovery.
                        if True:
                            connection.execute(
                                "UPDATE items SET barcode=?,condition_notes=? WHERE item_id=? AND record_revision=0 AND working_source='IMPORT' AND barcode='' AND condition_notes=''",
                                (
                                    group["barcode"].get("selected") or "",
                                    group.get("note", ""),
                                    iid,
                                ),
                            )
                        for photo in group["photos"]:
                            stream += 1
                            metadata = canonical(
                                {
                                    "session_id": manifest["session"]["id"],
                                    "capture_group_id": group["id"],
                                    "photo_id": photo["id"],
                                    "barcode": group["barcode"],
                                    "role": photo["role"],
                                }
                            ).decode()
                            connection.execute(
                                "UPDATE photos SET image_role=?,capture_metadata_json=?,capture_source='ANDROID_MOBILE_V1' WHERE batch_id=? AND stream_index=?",
                                (photo["role"].lower(), metadata, result.batch_id, stream),
                            )
                        receipts.execute(
                            "INSERT INTO groups VALUES(?,?,?,?)",
                            (group["id"], signature, result.batch_id, iid),
                        )
                        mappings.append(
                            {
                                "capture_group_id": group["id"],
                                "batch_id": result.batch_id,
                                "item_id": iid,
                            }
                        )
            receipt = {
                "format": "snapims.mobile.receipt",
                "version": 1,
                "archive_id": archive_id,
                "session_id": manifest["session"]["id"],
                "groups": mappings,
                "duplicate": not bool(new_groups),
            }
            # Preserve provenance before committing receipt; repeat import can reconstruct after interruption.
            saved = root / f"{archive_id}.json"
            tmp = saved.with_suffix(".pending")
            with tmp.open("wb") as output:
                output.write(canonical({"manifest": manifest, "receipt": receipt}))
                output.flush()
                os.fsync(output.fileno())
            os.replace(tmp, saved)
            receipts.execute(
                "INSERT INTO archives VALUES(?,?,?)",
                (archive_id, digest, canonical(receipt).decode()),
            )
            receipts.commit()
            return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument(
        "--data-dir",
        type=Path,
        required=True,
        help="Explicit SnapIMS data root; use a disposable root for acceptance testing",
    )
    parser.add_argument(
        "--location", default="", help="Fallback for unlocated groups, A1-J10 or Q1"
    )
    args = parser.parse_args()
    try:
        receipt = import_mobile(
            args.archive, paths=DataPaths.from_root(args.data_dir), location=args.location
        )
    except (MobileImportError, OSError) as exc:
        parser.exit(2, f"Mobile import refused: {exc}\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Read-only SnapIMS 0.15 inventory/photo export for SnapIMS Next (Room 3).

No SQL writes, backend, Shopify calls or credentials. Run against an explicit DB;
original photo references must stay under the chosen media root.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import sqlite3
from urllib.parse import quote
from uuid import NAMESPACE_URL, uuid5
import zipfile
from zoneinfo import ZoneInfo

TABLES = ['sessions','groups','photos','archives','events','recognition','inventory_batches','inventory','inventory_audit','transfer_jobs']
CONDITIONS = {'Not Graded','Fair','Good','Very Good','Like New','Sealed','Damaged','Mold Review'}

def stable(kind: str, identifier: str) -> str:
    return str(uuid5(NAMESPACE_URL, f'snapims:{kind}:{identifier}'))

def timestamp(value: str, source_timezone: str | None = None) -> str:
    parsed = datetime.fromisoformat(value.replace('Z','+00:00'))
    if parsed.tzinfo is None:
        if not source_timezone:
            raise ValueError('Source timestamps have no timezone. Supply --source-timezone with the verified original capture timezone.')
        zone = ZoneInfo(source_timezone)
        first, second = parsed.replace(tzinfo=zone, fold=0), parsed.replace(tzinfo=zone, fold=1)
        if first.utcoffset() != second.utcoffset():
            raise ValueError('A source timestamp is ambiguous or nonexistent at a clock change; resolve it before exporting.')
        parsed = first
    return parsed.isoformat()

def file_hash(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

def export_batch(database: Path, batch_id: str, output: Path, *, media_root: Path | None = None, weight_grams: int = 250, source_timezone: str | None = None) -> dict:
    database = database.resolve(strict=True)
    root = (media_root or database.parent.parent).resolve(strict=True)
    if not 1 <= weight_grams <= 1_000_000:
        raise ValueError('Unsupported default shipping weight')
    tables = {name: [] for name in TABLES}
    files = []
    paths: dict[str,Path] = {}
    with sqlite3.connect(f'file:{quote(str(database))}?mode=ro', uri=True) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute('PRAGMA query_only=ON')
        batch_row = connection.execute('SELECT * FROM batches WHERE batch_id=?',(batch_id,)).fetchone()
        if batch_row is None:
            raise ValueError('Batch ID not found')
        batch = dict(batch_row)
        session_id = stable('batch',batch_id)
        created = timestamp(batch['created_at'],source_timezone)
        tables['sessions'].append(dict(id=session_id,name=str(batch.get('display_name') or batch_id)[:80],createdAt=created,location='',finished=1))
        tables['inventory_batches'].append(dict(sessionId=session_id,batchId=batch_id))
        items = connection.execute('SELECT * FROM items WHERE batch_id=? ORDER BY sequence',(batch_id,)).fetchall()
        if not items:
            raise ValueError('Cannot export an empty batch')
        for raw in items:
            item = dict(raw); iid = item['item_id']; gid = stable('item',iid)
            if item['sku'] != iid:
                raise ValueError(f'SKU differs from immutable Item ID: {iid}')
            location = str(item.get('location') if item.get('location') is not None else item.get('shelf') or '')
            condition = item.get('condition') or 'Not Graded'
            if condition not in CONDITIONS:
                raise ValueError(f'Unsupported condition for {iid}')
            item_created = timestamp(item['created_at'],source_timezone); updated = timestamp(item['updated_at'],source_timezone)
            tables['groups'].append(dict(id=gid,sessionId=session_id,number=int(item['sequence']),createdAt=item_created,location=location,closed=1,
                rare=int(bool(item['rare'])),review=int(bool(item['review'])),note='',barcodeOverride=None,archiveId=None))
            price = item.get('price_cents')
            discount = int((Decimal(str(item.get('discount_percent') or 0)).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)*100))
            photos = connection.execute("SELECT * FROM photos WHERE item_id=? AND kind='product' ORDER BY photo_order,photo_id",(iid,)).fetchall()
            if not photos:
                raise ValueError(f'No product originals for {iid}')
            for index, raw_photo in enumerate(photos):
                photo = dict(raw_photo)
                source = Path(photo['original_copy_path']).resolve(strict=True)
                if not source.is_relative_to(root) or not source.is_file():
                    raise ValueError(f'Original is outside the media root: {iid}')
                sha = file_hash(source)
                if sha != photo['sha256']:
                    raise ValueError(f'Original checksum mismatch: {iid}')
                if not 4 <= source.stat().st_size <= 250_000_000:
                    raise ValueError(f'Unsupported photo size: {iid}')
                pid = stable('photo',f'{batch_id}:{photo["photo_id"]}')
                path = f'originals/{pid}.jpg'; paths[path] = source
                files.append(dict(path=path,bytes=source.stat().st_size,sha256=sha))
                role = str(photo.get('image_role') or '').upper()
                if role not in {'FRONT','BACK','ADDITIONAL'}:
                    role = 'FRONT' if index==0 else 'BACK' if index==1 else 'ADDITIONAL'
                tables['photos'].append(dict(id=pid,groupId=gid,position=index,role=role,capturedAt=timestamp(photo['captured_at'],source_timezone),state='READY',
                    bytes=source.stat().st_size,sha256=sha,deleted=0,barcodeJson='[]',scanState='OFF',error=''))
            native = dict(groupId=gid,itemId=iid,batchId=batch_id,title=item.get('title') or '',releaseYear=item.get('release_year'),edition=item.get('edition') or '',
                distributor=item.get('distributor') or '',condition=condition,conditionNotes=item.get('condition_notes') or '',location=location,
                barcode=item.get('barcode') or '',priceCents=price,discountBasisPoints=discount,quantity=int(item.get('quantity',1)),poolMode=item.get('pool_mode') or 'POOLED',
                vendor=item.get('vendor') or 'Canada VHS',productType=item.get('product_type') or 'VHS Tape',tags=item.get('tags') or '',description=item.get('description') or '',
                weightGrams=weight_grams,rare=int(bool(item['rare'])),review=int(bool(item['review'])),
                ready=int(bool(item.get('ready')) and item.get('review_status')=='DONE'),archived=0,priceSource='IMPORTED_DESKTOP' if price is not None else 'UNPRICED',
                priceEvidenceJson=json.dumps({'source':'SnapIMS desktop inventory','original_price_cents':price},sort_keys=True),recognitionId='',captureNote='',
                shopifyProductId=item.get('shopify_product_id') or '',shopifyVariantId=item.get('shopify_variant_id') or '',shopifyInventoryItemId=item.get('shopify_inventory_item_id') or '',
                shopifyState=item.get('upload_status') or 'NOT_UPLOADED',revision=int(item.get('record_revision') or 0),createdAt=item_created,updatedAt=updated)
            tables['inventory'].append(native)
    snapshot = dict(format='snapims.inventory.snapshot',version=1,schema=3,tables=tables,files=files)
    output = output.resolve()
    if output.exists():
        raise FileExistsError('Choose a new output filename; existing files are never replaced.')
    output.parent.mkdir(parents=True,exist_ok=True)
    pending = output.with_name(output.name+'.pending')
    if pending.exists():
        raise FileExistsError('A previous pending export exists; inspect it before retrying.')
    try:
        with zipfile.ZipFile(pending,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
            archive.writestr('snapshot.json',json.dumps(snapshot,sort_keys=True,separators=(',',':')))
            for relative, source in paths.items():
                archive.write(source,relative)
        pending.rename(output)
    except Exception:
        # Only our incomplete staging file may be removed; no source inventory writes.
        pending.unlink(missing_ok=True)
        raise
    return dict(format=snapshot['format'],batch_id=batch_id,items=len(tables['inventory']),photos=len(files),sha256=file_hash(output),output=str(output))

def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database',type=Path,required=True)
    parser.add_argument('--batch-id',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--media-root',type=Path)
    parser.add_argument('--source-timezone',help='Verified IANA timezone for legacy timestamps without an offset')
    parser.add_argument('--default-weight-grams',type=int,default=250)
    args=parser.parse_args()
    print(json.dumps(export_batch(args.database,args.batch_id,args.output,media_root=args.media_root,weight_grams=args.default_weight_grams,source_timezone=args.source_timezone),indent=2))

if __name__=='__main__':
    main()

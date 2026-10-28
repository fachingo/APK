package ca.canadavhs.snapmobile

import android.graphics.BitmapFactory
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun InventoryRow.databaseJson(): JSONObject = inventoryJson(this).apply {
    remove("sku"); put("rare",if(rare) 1 else 0); put("review",if(review) 1 else 0)
    put("ready",if(ready) 1 else 0); put("archived",if(archived) 1 else 0)
}
internal fun sessionJson(row: SessionRow) = JSONObject().put("id",row.id).put("name",row.name).put("createdAt",row.createdAt).put("location",row.location).put("finished",if(row.finished) 1 else 0)
internal fun groupJson(row: GroupRow) = JSONObject().put("id",row.id).put("sessionId",row.sessionId).put("number",row.number).put("createdAt",row.createdAt)
    .put("location",row.location).put("closed",if(row.closed) 1 else 0).put("rare",if(row.rare) 1 else 0).put("review",if(row.review) 1 else 0)
    .put("note",row.note).put("barcodeOverride",row.barcodeOverride ?: JSONObject.NULL).put("archiveId",row.archiveId ?: JSONObject.NULL)
internal fun photoJson(row: PhotoRow) = JSONObject().put("id",row.id).put("groupId",row.groupId).put("position",row.position).put("role",row.role)
    .put("capturedAt",row.capturedAt).put("state",row.state).put("bytes",row.bytes).put("sha256",row.sha256).put("deleted",if(row.deleted) 1 else 0)
    .put("barcodeJson",row.barcodeJson).put("scanState",row.scanState).put("error",row.error)
internal fun newSnapshot() = JSONObject().put("format","snapims.inventory.snapshot").put("version",1).put("schema",3)
    .put("tables",JSONObject().apply { snapshotTables.forEach { put(it,JSONArray()) } }).put("files",JSONArray())
internal fun requireUuid(value: String): String {
    require(runCatching { UUID.fromString(value).toString() == value.lowercase(java.util.Locale.ROOT) }.getOrDefault(false)) { "Invalid capture identity." }; return value
}
internal fun requireJpeg(file: File) {
    check(file.length() in 4..250_000_000L) { "Unsupported original JPEG size." }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path,options)
    require(options.outWidth in 1..20000 && options.outHeight in 1..20000 && options.outMimeType == "image/jpeg") { "Original is not a supported JPEG." }
    java.io.RandomAccessFile(file,"r").use { input -> require(input.readUnsignedShort()==0xffd8); input.seek(input.length()-2); require(input.readUnsignedShort()==0xffd9) { "Incomplete original JPEG." } }
}

suspend fun CaptureRepository.createPortableInventoryExport(): File = transferJob("EXPORT",".zip") { row,stage ->
    val snapshot = hashSnapshotFiles(buildSnapshot()); val metadata = canonicalJson(snapshot).toByteArray(Charsets.UTF_8)
    require(metadata.size <= MAX_SNAPSHOT_BYTES)
    val files = snapshot.getJSONArray("files"); val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
    check(freeSpace(stage) > total + 30_000_000) { "Not enough space to create the photo-inclusive export." }
    val temporary = File(stage,"export.pending")
    FileOutputStream(temporary).use { output -> ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("snapshot.json")); zip.write(metadata); zip.closeEntry()
        for(i in 0 until files.length()) {
            val record = files.getJSONObject(i); val path = record.getString("path"); val source = snapshotFile(path)
            check(source.length()==record.getLong("bytes") && hashFile(source)==record.getString("sha256"))
            zip.putNextEntry(ZipEntry(path)); source.inputStream().use { check(copyCancellable(it,zip,record.getLong("bytes"))==record.getLong("bytes")) }; zip.closeEntry()
            if(i%5==0 || i==files.length()-1) journal.inventoryDao().update(row.copy(total=files.length(),completed=i+1))
        }
    } }
    FileOutputStream(temporary,true).use { it.fd.sync() }
    val target = File(archiveDir,row.filename); atomicMove(temporary,target); sync(archiveDir)
    journal.inventoryDao().update(row.copy(total=files.length(),completed=files.length(),sha256=hashFile(target)))
    target
}
suspend fun CaptureRepository.importInventoryPackage(source: File): Int = transferJob("IMPORT",".zip") { row,stage ->
    val files = File(stage,"files").apply { mkdirs() }
    val snapshot = ZipFile(source).use { zip ->
        if(zip.getEntry("snapshot.json") != null) stageSnapshot(source,files) else stageMobileArchive(source,files)
    }
    val count = mergeSnapshot(snapshot,files)
    journal.inventoryDao().update(row.copy(total=count,completed=count)); count
}

internal suspend fun CaptureRepository.stageMobileArchive(source: File, stage: File): JSONObject = withContext(Dispatchers.IO) {
    ZipFile(source).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= MAX_PACKAGE_FILES+1 && entries.map { it.name }.distinct().size == entries.size && entries.none { it.isDirectory })
        val entry = zip.getEntry("manifest.json") ?: error("Select a SnapIMS portable inventory ZIP, native capture ZIP, or protected backup.")
        require(entry.size in 1..5_000_000)
        val manifestBytes = zip.getInputStream(entry).use { it.readBounded(5_000_000) }; require(manifestBytes.size <= 5_000_000)
        val manifest = JSONObject(String(manifestBytes,Charsets.UTF_8))
        require(manifest.getString("format")=="snapims.mobile" && manifest.getInt("version")==1) { "Unsupported native capture manifest." }
        val sessionValue = manifest.getJSONObject("session")
        val session = SessionRow(id=requireUuid(sessionValue.getString("id")), name=sessionValue.getString("name").also { require(it.length in 1..80) },
            createdAt=sessionValue.getString("created_at").also { OffsetDateTime.parse(it) }, location="",finished=true)
        val snapshot = newSnapshot(); val tables = snapshot.getJSONObject("tables"); tables.getJSONArray("sessions").put(sessionJson(session))
        val groups = manifest.getJSONArray("groups"); require(groups.length() in 1..10000)
        val named = mutableSetOf("manifest.json"); var total = 0L; var batchId: String? = null
        for(i in 0 until groups.length()) {
            currentCoroutineContext().ensureActive()
            val value = groups.getJSONObject(i); val id = requireUuid(value.getString("id")); val number = value.getInt("number"); require(number>0)
            val location = value.optString("location"); require(InventoryRules.validInventoryLocation(location))
            val barcode = value.optJSONObject("barcode")?.optString("selected")?.takeUnless { it=="null" } ?: ""
            require(barcode.isEmpty() || barcode.length in listOf(8,12,13,14) && barcode.all { it in '0'..'9' })
            val group = GroupRow(id=id,sessionId=session.id,number=number,createdAt=value.getString("captured_at").also { OffsetDateTime.parse(it) },location=location,closed=true,
                rare=value.optBoolean("rare"),review=value.optBoolean("review"),note=value.optString("note").take(1000))
            val existing = value.optJSONObject("inventory")
            val confirmed = value.optJSONArray("recognition")?.let { values -> (0 until values.length()).map { values.getJSONObject(it).optString("confirmed_title").takeUnless { text -> text=="null" } ?: "" }.firstOrNull { it.isNotBlank() } } ?: ""
            val item = if(existing != null) inventoryFromJson(existing) else {
                val batch = InventoryRules.batchId(session.createdAt,session.id)
                InventoryRow(id,InventoryRules.itemId(batch,location,number),batch,title=confirmed.take(255),barcode=barcode,location=location,
                    rare=group.rare,review=group.review,poolMode=if(group.rare) "UNIQUE" else "POOLED",captureNote=group.note,createdAt=group.createdAt)
            }
            require(item.groupId==id && (batchId==null || batchId==item.batchId)); batchId=item.batchId
            tables.getJSONArray("groups").put(groupJson(group)); tables.getJSONArray("inventory").put(item.databaseJson())
            val photos = value.getJSONArray("photos"); require(photos.length()>0)
            for(j in 0 until photos.length()) {
                val p = photos.getJSONObject(j); val photoId = requireUuid(p.getString("id")); require(p.getInt("order")==j+1)
                val filename = p.getString("filename"); require(filename.matches(Regex("[A-Za-z0-9._-]{1,180}\\.jpg")) && named.add(filename))
                val bytes = p.getLong("bytes"); require(bytes in 4..250_000_000L); total=Math.addExact(total,bytes)
                require(total <= MAX_PACKAGE_BYTES && freeSpace(stage) > bytes+30_000_000)
                val raw = zip.getEntry(filename) ?: error("Capture ZIP is missing a photo."); require(raw.size==bytes)
                val path = "originals/$photoId.jpg"; val target = File(stage,path).apply { parentFile!!.mkdirs() }
                zip.getInputStream(raw).use { input -> FileOutputStream(target).use { output -> check(copyCancellable(input,output,bytes)==bytes); output.fd.sync() } }
                val hash = p.getString("sha256"); require(hash.matches(Regex("[a-f0-9]{64}")) && hashFile(target)==hash); requireJpeg(target)
                val photo = PhotoRow(id=photoId,groupId=id,position=j,role=p.getString("role").also { Role.valueOf(it) },capturedAt=p.getString("captured_at").also { OffsetDateTime.parse(it) },state="READY",bytes=bytes,sha256=hash,scanState="OFF")
                tables.getJSONArray("photos").put(photoJson(photo)); snapshot.getJSONArray("files").put(JSONObject().put("path",path).put("bytes",bytes).put("sha256",hash))
            }
        }
        require(entries.map { it.name }.toSet()==named) { "Unexpected files in native capture ZIP." }
        tables.getJSONArray("inventory_batches").put(JSONObject().put("sessionId",session.id).put("batchId",batchId))
        validateSnapshot(snapshot); snapshot
    }
}

suspend fun CaptureRepository.exportInventoryCsv(): File = transferJob("CSV",".csv") { row,stage ->
    val temporary = File(stage,"inventory.csv")
    fun quote(value: Any?): String = "\""+(value?.toString() ?: "").replace("\"","\"\"")+"\""
    val items = journal.inventoryDao().items()
    temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write("\uFEFF")
        writer.appendLine(listOf("Item ID","SKU","Batch ID","Shelf","Title","Release year","Edition","Distributor","Barcode","Condition","Condition notes","Price","Discount percent","Quantity","Tags","Product type","Vendor","Description","Rare flag","Review flag","Ready status","Shopify status","Shopify product ID","Shopify variant ID","Shopify inventory item ID").joinToString(",",transform=::quote))
        items.forEach { item ->
            currentCoroutineContext().ensureActive()
            val group=dao.group(item.groupId); val barcode=effectiveBarcode(item,group,dao.photos(group.id))
            writer.appendLine(listOf(item.itemId,item.itemId,item.batchId,item.location,item.title,item.releaseYear,item.edition,item.distributor,barcode,item.condition,item.conditionNotes,
                InventoryRules.priceText(item.priceCents),java.math.BigDecimal(item.discountBasisPoints).movePointLeft(2).toPlainString(),item.quantity,item.tags,item.productType,item.vendor,item.description,
                if(item.rare) "TRUE" else "FALSE",if(item.review) "TRUE" else "FALSE",if(item.ready && !item.archived) "TRUE" else "FALSE",item.shopifyState,item.shopifyProductId,item.shopifyVariantId,item.shopifyInventoryItemId).joinToString(",",transform=::quote))
        }
    }
    FileOutputStream(temporary,true).use { it.fd.sync() }; val target=File(archiveDir,row.filename); atomicMove(temporary,target); sync(archiveDir)
    journal.inventoryDao().update(row.copy(total=items.size,completed=items.size,sha256=hashFile(target))); target
}

package ca.canadavhs.snapmobile

import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.canadavhs.snapmobile.core.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun candidates(photos: List<PhotoRow>): JSONArray {
    val values = mutableListOf<JSONObject>()
    photos.filter { !it.deleted }.forEach { p ->
        val array = JSONArray(p.barcodeJson)
        for (i in 0 until array.length()) values += array.getJSONObject(i)
    }
    return JSONArray(values.distinctBy { "${it.optString("raw")}:${it.optString("format")}" })
}
fun barcodeInfo(group: GroupRow, photos: List<PhotoRow>): JSONObject {
    val values = candidates(photos)
    val valid = (0 until values.length()).map { values.getJSONObject(it) }.filter { it.getBoolean("valid") }.distinctBy { it.getString("normalized") }
    val pending = photos.any { !it.deleted && it.scanState == "PENDING" }
    val error = photos.any { !it.deleted && it.scanState == "ERROR" }
    val status = if (group.barcodeOverride != null) (if (group.barcodeOverride.isEmpty()) "NO_BARCODE" else "MANUAL") else when {
        pending -> "SCANNING"; error -> "SCAN_ERROR"; valid.size > 1 -> "AMBIGUOUS"; valid.size == 1 -> "FOUND"; else -> "NO_BARCODE"
    }
    return JSONObject().put("status", status).put("candidates", values)
        .put("selected", group.barcodeOverride ?: valid.singleOrNull()?.getString("raw") ?: JSONObject.NULL)
}
class BarcodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = (applicationContext as CaptureApplication).repository
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val photo = repo.dao.photo(id)
        if (photo.deleted || photo.state != "READY") return@withContext Result.success()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
        var bitmap: android.graphics.Bitmap? = null
        try {
            // Decode a derivative, preserving original bytes and using EXIF for rotation.
            val file = repo.original(id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 2200).coerceAtLeast(1)) }
            bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Cannot decode barcode preview")
            val rotation = ExifInterface(file).rotationDegrees
            val found = scanner.process(InputImage.fromBitmap(bitmap, rotation)).await()
            val formats = mapOf(Barcode.FORMAT_UPC_A to "UPC_A", Barcode.FORMAT_UPC_E to "UPC_E", Barcode.FORMAT_EAN_13 to "EAN_13", Barcode.FORMAT_EAN_8 to "EAN_8", Barcode.FORMAT_CODE_128 to "CODE_128", Barcode.FORMAT_CODE_39 to "CODE_39", Barcode.FORMAT_ITF to "ITF")
            val values = found.mapNotNull { b -> val raw = b.rawValue ?: return@mapNotNull null; val format = formats[b.format] ?: return@mapNotNull null; Barcodes.candidate(raw, format) }
            val array = JSONArray(values.distinct().map { JSONObject().put("raw", it.raw).put("format", it.format).put("normalized", it.normalized).put("valid", it.valid).put("provider", "bundled-mlkit-17.3.0").put("photo_id", id) })
            // Read again: a concurrent tombstone/reorder must never be overwritten.
            repo.journal.withTransactionCompat { val latest = repo.dao.photo(id); repo.dao.update(latest.copy(barcodeJson = array.toString(), scanState = "DONE", error = "")) }
            Result.success()
        } catch (e: Exception) {
            repo.journal.withTransactionCompat { val latest = repo.dao.photo(id); repo.dao.update(latest.copy(scanState = "ERROR", error = "Barcode scan failed; manual correction or rescan is available.")) }
            Result.failure()
        } finally { scanner.close(); bitmap?.recycle() }
    }
}
// Keep concurrent worker updates transactional without holding a camera mutation lock.
private suspend fun <T> CaptureJournal.withTransactionCompat(block: suspend () -> T): T = this.withTransaction { block() }

class ArchiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = (applicationContext as CaptureApplication).repository
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        var archive = repo.dao.archive(id)
        if (archive.state == "READY") return@withContext Result.success()
        val target = File(repo.archiveDir, archive.filename); val temporary = File(repo.archiveDir, "${archive.id}.pending")
        try {
            val ids = JSONArray(archive.groupIds)
            val groups = (0 until ids.length()).map { repo.dao.group(ids.getString(it)) }
            val groupPhotos = groups.associate { it.id to repo.dao.photos(it.id).filter { p -> !p.deleted && p.state == "READY" } }
            if (groupPhotos.values.flatten().any { it.scanState == "PENDING" }) return@withContext Result.retry()
            val manifest = if (archive.manifestJson.isEmpty()) mobileManifest(repo, archive, groups, groupPhotos) else JSONObject(archive.manifestJson)
            if (archive.manifestJson.isEmpty()) {
                archive = archive.copy(manifestJson = manifest.toString())
                repo.dao.update(archive)
            }
            val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
            val total = groupPhotos.values.flatten().sumOf { it.bytes }
            check(repo.archiveDir.usableSpace > total + 30_000_000) { "Not enough free space for this archive. Original photos are safe." }
            repo.dao.update(archive.copy(state = "BUILDING", error = ""))
            // An existing target may be a completed rename from an interrupted job.
            if (target.exists() && runCatching { verifyArchive(target, manifest) }.isFailure) {
                // Preserve suspect output for diagnostics; rebuild from verified originals.
                atomicMove(target, File(repo.archiveDir, "${archive.id}.damaged-${System.currentTimeMillis()}.zip"))
                repo.sync(repo.archiveDir)
            }
            if (!target.exists()) {
                FileOutputStream(temporary).use { output ->
                    ZipOutputStream(output.buffered()).use { zip ->
                        storedBytes(zip, "manifest.json", manifestBytes)
                        groups.forEach { group ->
                            val photos = groupPhotos.getValue(group.id)
                            val code = barcodeInfo(group, photos).optString("selected").takeUnless { it == "null" }
                            photos.forEachIndexed { index, p ->
                                val file = repo.original(p.id)
                                check(file.length() == p.bytes && hashFile(file) == p.sha256) { "Original integrity mismatch: ${p.id}. Archive stopped; files retained." }
                                val crc = CRC32(); file.inputStream().buffered().use { input -> val buffer = ByteArray(256 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) } }
                                val entry = ZipEntry(photoFilename(group.createdAt, code, group.id, index, Role.valueOf(p.role))).apply { method = ZipEntry.STORED; size = p.bytes; compressedSize = p.bytes; this.crc = crc.value }
                                zip.putNextEntry(entry); file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
                            }
                        }
                    }
                }
                FileOutputStream(temporary, true).use { it.fd.sync() }
                verifyArchive(temporary, manifest)
                atomicMove(temporary, target); repo.sync(repo.archiveDir)
            } else verifyArchive(target, manifest)
            repo.dao.update(archive.copy(state = "READY", bytes = target.length(), sha256 = hashFile(target), error = ""))
            Result.success()
        } catch (e: Exception) {
            repo.dao.update(archive.copy(state = "ERROR", error = (e.message ?: "Archive failed; originals retained.").take(400)))
            Result.failure()
        }
    }
}
private fun storedBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc.value }); zip.write(bytes); zip.closeEntry()
}
suspend fun mobileManifest(repo: CaptureRepository, archive: ArchiveRow, groups: List<GroupRow>, photos: Map<String, List<PhotoRow>>): JSONObject {
    val session = repo.dao.session(archive.sessionId)
    return JSONObject().put("format", "snapims.mobile").put("version", 1).put("archive_id", archive.id).put("part", archive.part).put("archive_target_bytes", archive.targetBytes).put("oversized_group_ids", JSONArray(groups.filter { g -> photos.getValue(g.id).sumOf { it.bytes } > archive.targetBytes }.map { it.id }))
        .put("session", JSONObject().put("id", session.id).put("name", session.name).put("created_at", session.createdAt))
        .put("groups", JSONArray(groups.map { group ->
            val list = photos.getValue(group.id); val barcode = barcodeInfo(group, list); val code = barcode.optString("selected").takeUnless { it == "null" }
            JSONObject().put("id", group.id).put("number", group.number).put("captured_at", group.createdAt).put("location", group.location)
                .put("rare", group.rare).put("review", group.review || barcode.getString("status") in setOf("AMBIGUOUS", "SCAN_ERROR") || list.none { it.role == "FRONT" } || list.none { it.role == "BACK" })
                .put("note", group.note).put("barcode", barcode)
                .put("photos", JSONArray(list.mapIndexed { i, p -> JSONObject().put("id", p.id).put("order", i + 1).put("role", p.role).put("captured_at", p.capturedAt).put("filename", photoFilename(group.createdAt, code, group.id, i, Role.valueOf(p.role))).put("bytes", p.bytes).put("sha256", p.sha256) }))
        }))
        .put("events", JSONArray(repo.dao.events(session.id).filter { it.groupId == null || groups.any { g -> g.id == it.groupId } }.map { JSONObject().put("id", it.id).put("group_id", it.groupId ?: JSONObject.NULL).put("action", it.action).put("created_at", it.createdAt) }))
}
fun verifyArchive(file: File, manifest: JSONObject) {
    ZipFile(file).use { zip ->
        val archivedManifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
        check(JSONObject(archivedManifest).toString() == manifest.toString()) { "Existing archive has different metadata." }
        val groups = manifest.getJSONArray("groups")
        var count = 1
        for (i in 0 until groups.length()) {
            val photos = groups.getJSONObject(i).getJSONArray("photos")
            for (j in 0 until photos.length()) {
                val p = photos.getJSONObject(j); val entry = zip.getEntry(p.getString("filename")) ?: error("Missing archive photo")
                check(entry.size == p.getLong("bytes")) { "Archive photo size mismatch" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input -> val buffer = ByteArray(256 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                check(digest.digest().joinToString("") { "%02x".format(it) } == p.getString("sha256")) { "Archive photo checksum mismatch" }; count++
            }
        }
        check(zip.size() == count) { "Unexpected archive members" }
    }
}

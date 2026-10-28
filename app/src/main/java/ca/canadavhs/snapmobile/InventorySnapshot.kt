package ca.canadavhs.snapmobile

import android.database.Cursor
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import ca.canadavhs.snapmobile.core.sha256

internal val snapshotTables = listOf("sessions","groups","photos","archives","events","recognition","inventory_batches","inventory","inventory_audit","transfer_jobs")
internal val snapshotPrimaryKeys = mapOf("inventory_batches" to "sessionId", "inventory" to "groupId")
internal const val MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
internal const val MAX_PACKAGE_FILES = 100_000
internal const val MAX_PACKAGE_BYTES = 100_000_000_000L

internal fun canonicalJson(value: Any?): String = when(value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> error("Unsupported snapshot value")
}
internal fun cursorObject(cursor: Cursor): JSONObject = JSONObject().apply {
    for(i in 0 until cursor.columnCount) put(cursor.getColumnName(i), when(cursor.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
        else -> error("Inventory snapshots cannot contain database blobs.")
    })
}
internal fun CaptureRepository.snapshotFile(path: String): File {
    val parts = path.split('/')
    require(parts.size == 2 && parts[0] in listOf("originals","archives") && parts[1].matches(Regex("[A-Za-z0-9._-]{1,180}")) && parts[1] !in listOf(".","..")) { "Unsafe photo/package path." }
    val directory = if(parts[0] == "originals") originals else archiveDir
    val file = File(directory, parts[1])
    require(file.canonicalFile.parentFile == directory.canonicalFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Unsafe symbolic link in package." }
    return file
}
internal suspend fun CaptureRepository.buildSnapshot(): JSONObject = mutate {
    check(dao.pending().isEmpty()) { "Wait for photographs to finish saving before backup." }
    check(dao.unfinishedArchives().none { it.state == "BUILDING" }) { "Wait for archive creation to finish before backup." }
    val tables = JSONObject()
    val db = journal.openHelper.writableDatabase
    snapshotTables.forEach { table ->
        val rows = JSONArray()
        val clause = if(table == "transfer_jobs") " WHERE state!='RUNNING'" else ""
        db.query("SELECT * FROM $table$clause").use { cursor -> while(cursor.moveToNext()) rows.put(cursorObject(cursor)) }
        tables.put(table, rows)
    }
    val files = linkedMapOf<String, File>()
    originals.listFiles()?.filter { it.isFile }?.forEach { file -> files["originals/${file.name}"] = snapshotFile("originals/${file.name}") }
    val archives = tables.getJSONArray("archives")
    for(i in 0 until archives.length()) {
        val row = archives.getJSONObject(i)
        val path = "archives/${row.getString("filename")}"
        val file = snapshotFile(path)
        if(file.exists()) files[path] = file
        val pending = "archives/${row.getString("id")}.pending"
        if(snapshotFile(pending).exists()) files[pending] = snapshotFile(pending)
    }
    require(files.size <= MAX_PACKAGE_FILES) { "Export separate batches: backup exceeds the package file limit." }
    val manifestFiles = JSONArray()
    // Hashing is performed after the consistent metadata snapshot, outside camera's lock.
    JSONObject().put("format","snapims.inventory.snapshot").put("version",1).put("schema",3)
        .put("tables",tables).put("files",manifestFiles).put("file_paths",JSONArray(files.keys.toList()))
}
internal suspend fun CaptureRepository.hashSnapshotFiles(snapshot: JSONObject): JSONObject = withContext(Dispatchers.IO) {
    val paths = snapshot.getJSONArray("file_paths"); snapshot.remove("file_paths")
    val files = snapshot.getJSONArray("files")
    var total = 0L
    for(i in 0 until paths.length()) {
        currentCoroutineContext().ensureActive()
        val path = paths.getString(i); val file = snapshotFile(path)
        check(file.isFile) { "A source file disappeared. Backup stopped; retry after review." }
        val size = file.length(); total = Math.addExact(total,size)
        check(total <= MAX_PACKAGE_BYTES) { "Export separate batches: backup exceeds package size limit." }
        files.put(JSONObject().put("path",path).put("bytes",size).put("sha256",hashFile(file)))
    }
    validateSnapshot(snapshot)
    snapshot
}
internal fun CaptureRepository.validateSnapshot(snapshot: JSONObject) {
    require(snapshot.getString("format") == "snapims.inventory.snapshot" && snapshot.getInt("version") == 1 && snapshot.getInt("schema") == 3) { "Unsupported inventory snapshot version." }
    val tables = snapshot.getJSONObject("tables")
    require(tables.keys().asSequence().toSet() == snapshotTables.toSet()) { "Incomplete inventory snapshot." }
    val files = snapshot.getJSONArray("files"); require(files.length() <= MAX_PACKAGE_FILES)
    val indexed = mutableMapOf<String,JSONObject>(); var total = 0L
    for(i in 0 until files.length()) {
        val row = files.getJSONObject(i); val path = row.getString("path"); snapshotFile(path)
        require(indexed.put(path,row) == null) { "Duplicate photo/package path." }
        val size = row.getLong("bytes"); require(size in 0..MAX_PACKAGE_BYTES)
        total = Math.addExact(total,size); require(total <= MAX_PACKAGE_BYTES)
        require(row.getString("sha256").matches(Regex("[a-f0-9]{64}")))
    }
    snapshotTables.forEach { table ->
        val rows = tables.getJSONArray(table); require(rows.length() <= 500_000)
        val primary = snapshotPrimaryKeys[table] ?: "id"; val ids = mutableSetOf<String>()
        for(i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val id = row.getString(primary)
            require(id.isNotBlank() && id.length <= 200 && ids.add(id)) { "Duplicate or invalid $table identity." }
            requireUuid(id)
            row.keys().forEach { key -> val value = row.get(key); require(value == JSONObject.NULL || value is String || value is Number || value is Boolean); if(value is String) require(value.length <= 1_000_000) }
            for(key in listOf("number","position","part","discountBasisPoints","quantity","weightGrams","completed","total")) if(row.has(key)) {
                val value=row.get(key); require(value is Int || value is Long); require((value as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            }
            for(key in listOf("finished","closed","rare","review","deleted","ready","archived")) if(row.has(key)) require(row.getLong(key) in 0..1)
            if(table=="groups") { requireUuid(row.getString("sessionId")); require(row.getInt("number")>0) }
            if(table=="photos") {
                requireUuid(row.getString("groupId")); require(row.getInt("position")>=0)
                require(row.getString("role") in listOf("FRONT","BACK","ADDITIONAL"))
                require(row.getString("state") in listOf("RESERVED","READY","ERROR"))
                require(row.getString("scanState") in listOf("PENDING","OFF","DONE","ERROR"))
            }
            if(table=="archives") snapshotFile("archives/${row.getString("filename")}")
            when(table) {
                "photos" -> if(row.getString("state") == "READY") {
                    val file = indexed["originals/$id.jpg"] ?: error("Backup is missing an original photograph.")
                    require(file.getLong("bytes") == row.getLong("bytes") && file.getString("sha256") == row.getString("sha256")) { "Photo checksum metadata mismatch." }
                }
                "archives" -> if(row.getString("state") == "READY") {
                    val file = indexed["archives/${row.getString("filename")}"] ?: error("Backup is missing a completed archive.")
                    require(file.getLong("bytes") == row.getLong("bytes") && file.getString("sha256") == row.getString("sha256"))
                }
                "inventory" -> {
                    val copy = JSONObject(row.toString()).put("sku",row.getString("itemId"))
                    for(key in listOf("rare","review","ready","archived")) {
                        require(row.getInt(key) in 0..1); copy.put(key,row.getInt(key)==1)
                    }
                    inventoryFromJson(copy)
                }
            }
        }
    }
}

internal suspend fun CaptureRepository.stageSnapshot(zip: File, stage: File): JSONObject = withContext(Dispatchers.IO) {
    ZipFile(zip).use { input ->
        val entries = input.entries().asSequence().toList()
        require(entries.size <= MAX_PACKAGE_FILES + 1 && entries.map { it.name }.distinct().size == entries.size) { "Duplicate or too many ZIP entries." }
        val manifest = input.getEntry("snapshot.json") ?: error("Inventory snapshot is missing.")
        require(manifest.size in 1..MAX_SNAPSHOT_BYTES.toLong()) { "Inventory snapshot is too large." }
        val text = input.getInputStream(manifest).use { stream -> stream.readBounded(MAX_SNAPSHOT_BYTES) }
        require(text.size <= MAX_SNAPSHOT_BYTES)
        val snapshot = JSONObject(String(text,Charsets.UTF_8)); validateSnapshot(snapshot)
        val files = snapshot.getJSONArray("files")
        val expected = (0 until files.length()).map { files.getJSONObject(it).getString("path") }.toSet() + "snapshot.json"
        require(entries.map { it.name }.toSet() == expected && entries.none { it.isDirectory }) { "Unexpected or missing package files." }
        val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
        check(freeSpace(stage) > total + 30_000_000) { "Not enough free space to stage this restore. Existing inventory is unchanged." }
        for(i in 0 until files.length()) {
            currentCoroutineContext().ensureActive()
            val row = files.getJSONObject(i); val path = row.getString("path"); val entry = input.getEntry(path)
            require(entry.size == row.getLong("bytes")) { "Photo/package byte count mismatch." }
            val target = File(stage,path); target.parentFile!!.mkdirs()
            input.getInputStream(entry).use { source -> FileOutputStream(target).use { out ->
                val buffer = ByteArray(262144); var copied = 0L
                while(true) { currentCoroutineContext().ensureActive(); val n = source.read(buffer); if(n<0) break; copied += n; require(copied <= row.getLong("bytes")); out.write(buffer,0,n) }
                require(copied == row.getLong("bytes")); out.fd.sync()
            } }
            require(hashFile(target) == row.getString("sha256")) { "Photo/package integrity check failed." }
        }
        val photos=snapshot.getJSONObject("tables").getJSONArray("photos")
        for(i in 0 until photos.length()) {
            val photo=photos.getJSONObject(i)
            if(photo.getString("state")=="READY") requireJpeg(File(stage,"originals/${photo.getString("id")}.jpg"))
        }
        snapshot
    }
}
internal suspend fun CaptureRepository.mergeSnapshot(snapshot: JSONObject, stage: File): Int = mutate {
    check(dao.pending().isEmpty()) { "Wait for captures before restoring inventory." }
    validateSnapshot(snapshot)
    val db = journal.openHelper.writableDatabase
    val tables = snapshot.getJSONObject("tables")
    val inserts = mutableListOf<Triple<String,List<String>,List<Any?>>>()
    // Preflight every record, including conflicts, before installing any file or row.
    snapshotTables.forEach { table ->
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor -> while(cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
        val primary = snapshotPrimaryKeys[table] ?: "id"
        val rows = tables.getJSONArray(table)
        for(i in 0 until rows.length()) {
            val row = JSONObject(rows.getJSONObject(i).toString())
            if(table=="inventory") {
                val sameId=journal.inventoryDao().itemById(row.getString("itemId"))
                require(sameId==null || sameId.groupId==row.getString("groupId")) { "Item ID already belongs to another physical-copy record. No data was overwritten." }
            }
            require(row.keys().asSequence().toSet() == columns.toSet()) { "Unsupported $table columns." }
            if(table == "recognition" && row.getString("state") in listOf("RUNNING","QUEUED")) {
                row.put("state","ERROR").put("workId","").put("error","Restored job requires manual retry; no provider request was restarted.")
            }
            if(table == "transfer_jobs" && row.getString("state") == "RUNNING") row.put("state","INTERRUPTED").put("error","Restored job requires manual retry.")
            val id = row.getString(primary)
            val existing = db.query("SELECT * FROM $table WHERE $primary=?", arrayOf(id)).use { cursor -> if(cursor.moveToFirst()) cursorObject(cursor) else null }
            if(existing != null) require(canonicalJson(existing) == canonicalJson(row)) { "Conflicting $table record $id. Existing data was not overwritten." }
            else inserts += Triple(table,columns,columns.map { key -> row.get(key).takeUnless { it == JSONObject.NULL } })
        }
    }
    val files = snapshot.getJSONArray("files")
    var needed = 0L
    for(i in 0 until files.length()) {
        val row = files.getJSONObject(i); val target = snapshotFile(row.getString("path"))
        if(target.exists()) require(target.length() == row.getLong("bytes") && hashFile(target) == row.getString("sha256")) { "Conflicting original file. No existing photo was replaced." }
        else needed += row.getLong("bytes")
    }
    check(freeSpace(originals) > needed + 30_000_000) { "Not enough free space to install restored files." }
    // Files precede the DB commit. A crash can leave verified orphan files; retry reuses
    // their hashes. It can never leave committed records pointing at unfinished files.
    for(i in 0 until files.length()) {
        currentCoroutineContext().ensureActive()
        val row = files.getJSONObject(i); val path = row.getString("path"); val target = snapshotFile(path)
        if(!target.exists()) {
            val source = File(stage,path)
            require(source.isFile && source.length() == row.getLong("bytes") && hashFile(source) == row.getString("sha256"))
            atomicMove(source,target)
        }
    }
    sync(originals); sync(archiveDir)
    for((table,columns,values) in inserts) db.execSQL("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})",values.toTypedArray())
    db.query("PRAGMA foreign_key_check").use { cursor -> check(!cursor.moveToFirst()) { "Restore contains broken record references." } }
    inserts.count { it.first == "inventory" }
}

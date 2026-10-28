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

internal val snapshotTables = listOf("sessions","groups","photos","archives","events","recognition","inventory_batches","inventory","inventory_audit","transfer_jobs","pricing_evidence_native","chat_conversations","chat_messages","ai_capabilities","agent_approvals","listing_plans","siwc_proofs","shopify_jobs","shopify_job_items","market_imports","price_decisions","market_searches","agent_tasks")
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
    require(parts.size == 2 && parts[0] in listOf("originals","archives","chat-attachments","market") && parts[1].matches(Regex("[A-Za-z0-9._-]{1,180}")) && parts[1] !in listOf(".","..")) { "Unsafe photo/package path." }
    val directory = when(parts[0]) { "originals" -> originals; "archives" -> archiveDir; "market" -> File(dataRoot,"market-exports").apply {mkdirs()}; else -> File(dataRoot,"chat-attachments").apply {mkdirs()} }
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
        db.query("SELECT * FROM $table$clause").use { cursor -> while(cursor.moveToNext()) rows.put(cursorObject(cursor).also {if(table=="shopify_job_items") it.put("checkpointJson",redactShopifyCheckpoint(it.getString("checkpointJson")))}) }
        tables.put(table, rows)
    }
    val files = linkedMapOf<String, File>()
    exportMarketArchiveLocked()?.let {file->files["market/${file.name}"]=file}
    originals.listFiles()?.filter { it.isFile }?.forEach { file -> files["originals/${file.name}"] = snapshotFile("originals/${file.name}") }
    File(dataRoot,"chat-attachments").listFiles()?.filter {it.isFile}?.forEach {files["chat-attachments/${it.name}"]=snapshotFile("chat-attachments/${it.name}")}
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
    JSONObject().put("format","snapims.inventory.snapshot").put("version",1).put("schema",7)
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
    require(snapshot.getString("format") == "snapims.inventory.snapshot" && snapshot.getInt("version") == 1 && snapshot.getInt("schema") in 3..7) { "Unsupported inventory snapshot version." }
    val tables = snapshot.getJSONObject("tables")
    if(snapshot.getInt("schema")<7) {
        val count=when(snapshot.getInt("schema")) {3->10;4->17;5->19;else->22}
        require(tables.keys().asSequence().toSet()==snapshotTables.take(count).toSet()) {"Incomplete legacy inventory snapshot."}
        snapshotTables.drop(count).forEach {tables.put(it,JSONArray())}
        val recognition=tables.getJSONArray("recognition");for(i in 0 until recognition.length()) recognition.getJSONObject(i).put("authBinding","").put("approvalId","")
        snapshot.put("schema",7)
    }
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
        val columns=linkedMapOf<String,Pair<String,Boolean>>()
        journal.openHelper.writableDatabase.query("PRAGMA table_info($table)").use {c->while(c.moveToNext()) columns[c.getString(c.getColumnIndexOrThrow("name"))]=c.getString(c.getColumnIndexOrThrow("type")) to (c.getInt(c.getColumnIndexOrThrow("notnull"))==1)}
        val rows = tables.getJSONArray(table); require(rows.length() <= 500_000)
        val primary = snapshotPrimaryKeys[table] ?: "id"; val ids = mutableSetOf<String>()
        for(i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.keys().asSequence().toSet()==columns.keys) {"Unsupported $table columns."}
            columns.forEach {(key,spec)->val value=row.get(key)
                if(value==JSONObject.NULL) require(!spec.second) {"Missing required $table field."}
                else when(spec.first) {
                    "TEXT" ->require(value is String) {"Invalid text in $table.$key"}
                    "INTEGER" ->require(value is Int || value is Long) {"Invalid integer in $table.$key"}
                    "REAL" ->require(value is Number) {"Invalid number in $table.$key"}
                    else ->error("Unsupported snapshot column type")
                }
            }
            val id = row.getString(primary)
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
                "agent_tasks" -> {
                    requireUuid(row.getString("approvalId"));requireUuid(row.getString("conversationId"));jsonIds(JSONArray(row.getString("groupIdsJson")));val jobs=JSONArray(row.getString("jobIdsJson"));require(jobs.length()<=50);for(j in 0 until jobs.length()) requireUuid(jobs.getString(j));JSONObject(row.getString("resultJson"))
                    require(row.getString("state") in listOf("SCHEDULE_PENDING","SCHEDULED","CANCEL_PENDING","CANCELLED","PAUSED_REVIEW","COMPLETE","OPEN"))
                    if(row.getString("kind")=="SHOPIFY_DRAFT") require(jobs.length()==1)
                    require(row.getString("kind") in listOf("RECOGNITION","MARKET_PRICE","SHOPIFY_DRAFT","REVIEW_BATCH"))
                }
                "price_decisions" -> {
                    require(row.getString("recordType") in listOf("OPERATOR_APPROVED_PRICE","MANUAL_ESTIMATE","AI_SUGGESTION"))
                    require(row.getLong("priceCents") in 0..ca.canadavhs.snapmobile.core.InventoryRules.maxPriceCents)
                    require(row.getString("currency")=="CAD");JSONArray(row.getString("evidenceJson"));JSONObject(row.getString("provenanceJson"))
                }
                "shopify_jobs" -> {
                    requireBrokerUrl(row.getString("broker"));require(row.getString("kind") in listOf("CREATE_DRAFT","PUBLISH_LIVE"))
                    require(row.getString("state") in listOf("QUEUED","RUNNING","WAITING_REMOTE","SUCCESS","PARTIAL_REVIEW","PAUSED","PAUSED_RECONNECT","CANCELLED"))
                }
                "shopify_job_items" -> {
                    requireUuid(row.getString("jobId"));requireUuid(row.getString("groupId"));requireUuid(row.getString("creationKey"))
                    inventoryFromJson(JSONObject(row.getString("snapshotJson")));JSONObject(row.getString("remoteJson"))
                    require(row.getString("checkpointJson")==redactShopifyCheckpoint(row.getString("checkpointJson"))) {"Backup must not contain temporary upload credentials."}
                }
                "chat_messages" -> {
                    require(row.getString("role") in listOf("user","assistant","tool"))
                    require(row.getString("status") in listOf("COMPLETE","PENDING","STREAMING","INTERRUPTED","CANCELLED","ERROR"))
                    val attachments=JSONArray(row.getString("attachmentsJson"));require(attachments.length()<=4)
                    for(j in 0 until attachments.length()) {val a=attachments.getJSONObject(j);val path="chat-attachments/${a.getString("path")}";snapshotFile(path)
                        require(a.getString("mime") in listOf("image/jpeg","image/png","application/pdf","text/plain"))
                        val file=indexed[path] ?: error("Backup is missing a chat attachment.")
                        require(file.getString("sha256")==a.getString("sha256") && file.getLong("bytes")==a.getLong("bytes") && file.getLong("bytes")<=8_000_000)
                    }
                    JSONArray(row.getString("toolCallsJson"))
                }
                "chat_conversations" -> {
                    val provider=AiProvider.get(row.getString("provider"))
                    require(row.getString("authMode")==if(provider.subscription) "CHATGPT_PLAN" else "API_KEY")
                    require(row.getString("model").length in 1..300)
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
            if(table=="agent_tasks" && row.getString("state") !in listOf("COMPLETE","CANCELLED")) row.put("state","PAUSED_REVIEW").put("error","Restored task requires fresh review; no external action restarted.")
            if(table=="inventory") {
                val sameId=journal.inventoryDao().itemById(row.getString("itemId"))
                require(sameId==null || sameId.groupId==row.getString("groupId")) { "Item ID already belongs to another physical-copy record. No data was overwritten." }
            }
            require(row.keys().asSequence().toSet() == columns.toSet()) { "Unsupported $table columns." }
            if(table=="shopify_jobs" && row.getString("state")!="SUCCESS") row.put("state","PAUSED_RECONNECT").put("workId","").put("error","Restored job requires device pairing and manual reconciliation; no Shopify write was restarted.")
            if(table=="shopify_job_items") row.put("checkpointJson",redactShopifyCheckpoint(row.getString("checkpointJson")))
            if(table == "recognition" && row.getString("state") in listOf("RUNNING","QUEUED")) {
                row.put("state","ERROR").put("workId","").put("error","Restored job requires manual retry; no provider request was restarted.")
            }
            if(table=="chat_messages" && row.getString("status") in listOf("PENDING","STREAMING")) row.put("status","INTERRUPTED").put("error","Restored response requires manual retry; no AI request was restarted.")
            if(table=="agent_approvals" && row.getString("state")=="PENDING") row.put("state","EXPIRED").put("resultJson","{\"error\":\"Restored approval requires a fresh proposal\"}")
            if(table == "transfer_jobs" && row.getString("state") == "RUNNING") row.put("state","INTERRUPTED").put("error","Restored job requires manual retry.")
            val id = row.getString(primary)
            val existing = db.query("SELECT * FROM $table WHERE $primary=?", arrayOf(id)).use { cursor -> if(cursor.moveToFirst()) cursorObject(cursor) else null }
            if(existing != null) require(canonicalJson(existing) == canonicalJson(row)) { "Conflicting $table record $id. Existing data was not overwritten." }
            else inserts += Triple(table,columns,columns.map { key -> row.get(key).takeUnless { it == JSONObject.NULL } })
        }
    }
    val newItems=inserts.filter {it.first=="inventory"}.map {it.third[it.second.indexOf("itemId")]}
    require(newItems.distinct().size==newItems.size) {"Duplicate physical Item IDs in the restore."}
    val newBatches=inserts.filter {it.first=="inventory_batches"}.map {it.third[it.second.indexOf("batchId")]}
    require(newBatches.distinct().size==newBatches.size) {"Duplicate batch identities in the restore."}
    val activeExisting=db.query("SELECT COUNT(*) FROM sessions WHERE finished=0").use {it.moveToFirst();it.getInt(0)}
    val activeNew=inserts.count {it.first=="sessions" && (it.third[it.second.indexOf("finished")] as Number).toLong()==0L}
    require(activeExisting+activeNew<=1) {"Finish the current capture session before merging another active session."}
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
            try {java.nio.file.Files.createLink(target.toPath(),source.toPath());java.nio.file.Files.delete(source.toPath())}
            catch(e:java.nio.file.FileAlreadyExistsException) {require(target.length()==row.getLong("bytes") && hashFile(target)==row.getString("sha256")) {"A conflicting file appeared during restore."}}
        }
    }
    sync(originals); sync(archiveDir); File(dataRoot,"chat-attachments").takeIf {it.isDirectory}?.let {sync(it)};sync(dataRoot)
    val marketFiles=(0 until files.length()).map {files.getJSONObject(it).getString("path")}.filter {it.startsWith("market/")}
    require(marketFiles.size<=1) {"Only one consistent market archive is allowed per snapshot."}
    if(marketFiles.isNotEmpty()) {ensureMarketSearch();mergeMarketArchiveLocked(snapshotFile(marketFiles.single()));File(dataRoot,"market-exports").takeIf {it.isDirectory}?.let {sync(it)}}
    for((table,columns,values) in inserts) db.execSQL("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})",values.toTypedArray())
    db.query("PRAGMA foreign_key_check").use { cursor -> check(!cursor.moveToFirst()) { "Restore contains broken record references." } }
    inserts.count { it.first == "inventory" }
}

internal fun redactShopifyCheckpoint(value:String):String = JSONObject(value).apply {
    optJSONObject("photos")?.let {photos->photos.keys().forEach {id->photos.getJSONObject(id).apply {if(has("target")) {remove("target");if(!has("fileId")) {put("uploaded",false);put("stageEpoch",optInt("stageEpoch")+1)}}}}}
}.toString()

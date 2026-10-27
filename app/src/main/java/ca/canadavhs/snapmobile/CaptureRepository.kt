package ca.canadavhs.snapmobile

import android.content.Context
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import androidx.work.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate

fun hashFile(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input -> val buf = ByteArray(256 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; hash.update(buf, 0, n) } }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
fun atomicMove(source: File, target: File) { java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
fun syncDirectory(directory: File) {
    val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
class CaptureRepository(val context: Context, val journal: CaptureJournal, private val directorySync: (File) -> Unit = ::syncDirectory) {
    fun sync(folder: File) = directorySync(folder)
    val dao = journal.dao()
    private val lock = Mutex()
    val originals = File(context.filesDir, "originals").apply { mkdirs() }
    val archiveDir = File(context.filesDir, "archives").apply { mkdirs() }
    val preferences = context.getSharedPreferences("capture-settings", Context.MODE_PRIVATE)
    fun original(id: String) = File(originals, "$id.jpg")
    fun pendingFile(id: String) = File(originals, "$id.pending")
    private suspend fun event(session: String, group: String?, action: String) = dao.insert(EventRow(sessionId = session, groupId = group, action = action))
    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { journal.withTransaction { block() } } }
    suspend fun start(name: String, location: String): SessionRow = mutate {
        check(dao.active() == null) { "Finish the current batch before starting another." }
        require(name.isNotBlank() && name.length <= 80) { "Use a batch name of 1–80 characters." }
        require(validLocation(location)) { "Location must be A1–J10, Q1, or blank." }
        val session = SessionRow(name = name.trim(), location = location)
        dao.insert(session); dao.insert(GroupRow(sessionId = session.id, number = 1, location = location))
        event(session.id, null, "CVHS1:BATCH:START"); session
    }
    suspend fun current(): GroupRow = dao.active()?.let { dao.groups(it.id).last { g -> !g.closed } } ?: error("Start a batch first.")
    private suspend fun editable(group: GroupRow) {
        check(group.archiveId == null) { "Exported groups are locked. Originals remain available." }
        check(!dao.session(group.sessionId).finished) { "This batch has finished." }
        check(dao.pending().isEmpty()) { "Wait for the photograph to finish saving." }
    }
    suspend fun reserve(): PhotoRow = mutate {
        val group = current(); editable(group)
        check(originals.usableSpace > 100L * 1024 * 1024) { "Less than 100 MB free. Export and back up captures, then free space before continuing." }
        val photos = dao.photos(group.id).filter { !it.deleted }
        val photo = PhotoRow(groupId = group.id, position = (photos.maxOfOrNull { it.position } ?: -1) + 1, role = defaultRole(photos.size).name)
        dao.insert(photo); photo
    }
    private fun completeJpeg(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        val tail = java.io.RandomAccessFile(file, "r").use { it.seek(it.length() - 2); it.readUnsignedShort() }
        return bounds.outWidth > 0 && bounds.outHeight > 0 && tail == 0xffd9
    }
    suspend fun complete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val photo = dao.photo(id)
            val pending = pendingFile(id); val target = original(id)
            check(completeJpeg(if (target.exists()) target else pending)) { "Camera file is incomplete. It has been retained for recovery." }
            if (!target.exists()) { FileOutputStream(pending, true).use { it.fd.sync() }; atomicMove(pending, target); sync(originals) }
            val ready = photo.copy(state = "READY", bytes = target.length(), sha256 = hashFile(target), error = "", scanState = if (photo.role == "BACK" && preferences.getBoolean("barcode", true)) "PENDING" else "OFF")
            dao.update(ready)
            enqueueScan(ready)
        }
    }
    suspend fun failed(id: String, message: String) = mutate {
        val photo = dao.photo(id); dao.update(photo.copy(state = "ERROR", error = message.take(400)))
    }
    suspend fun recover() = withContext(Dispatchers.IO) {
        dao.pending().forEach { photo -> runCatching { complete(photo.id) }.onFailure { failed(photo.id, "Interrupted capture. Original/pending file retained; inspect in Batches.") } }
        // Retry valid but previously interrupted writes without deleting incomplete files.
        dao.pendingScans().forEach { enqueueScan(it) }
        dao.unfinishedArchives().forEach { enqueueArchive(it.id) }
    }
    suspend fun next() {
        val session = mutate {
            val group = current(); editable(group)
            val photos = dao.photos(group.id).filter { !it.deleted && it.state == "READY" }
            check(photos.isNotEmpty()) { "Capture at least one photo before Next." }
            check(dao.photos(group.id).none { it.state == "ERROR" && !it.deleted }) { "Review the interrupted capture before Next." }
            dao.update(group.copy(closed = true)); event(group.sessionId, group.id, "CVHS1:ITEM:NEXT")
            dao.insert(GroupRow(sessionId = group.sessionId, number = group.number + 1, location = dao.session(group.sessionId).location))
            group.sessionId
        }
        val groups = dao.groups(session).filter { it.closed && it.archiveId == null }
        val bytes = groups.sumOf { g -> dao.photos(g.id).filter { !it.deleted }.sumOf { it.bytes } }
        if (bytes >= archiveLimit()) queueArchives(session, automatic = true)
    }
    suspend fun undoNext() = mutate {
        val current = current(); editable(current)
        check(dao.photos(current.id).none { !it.deleted }) { "Move or delete current photos before Undo Next." }
        val previous = dao.groups(current.sessionId).lastOrNull { it.number < current.number } ?: error("There is no previous product.")
        editable(previous)
        val offset = (dao.photos(previous.id).maxOfOrNull { it.position } ?: -1) + 1
        dao.photos(current.id).forEachIndexed { i, p -> dao.update(p.copy(groupId = previous.id, position = offset + i)) }
        dao.removeEmptyGroup(current.id); dao.update(previous.copy(closed = false))
        event(previous.sessionId, previous.id, "UNDO_NEXT")
    }
    suspend fun finish() {
        val session = mutate {
            val group = current(); editable(group)
            check(dao.photos(group.id).none { it.state != "READY" && !it.deleted }) { "Review interrupted captures before finishing." }
            dao.update(group.copy(closed = true)); dao.update(dao.session(group.sessionId).copy(finished = true))
            event(group.sessionId, group.id, "CVHS1:BATCH:END"); group.sessionId
        }
        queueArchives(session)
    }
    suspend fun location(value: String) = mutate {
        require(validLocation(value)) { "Use A1–J10, Q1, or blank." }
        val group = current(); editable(group)
        dao.update(dao.session(group.sessionId).copy(location = value)); dao.update(group.copy(location = value)); event(group.sessionId, group.id, "CVHS1:LOC:$value")
    }
    suspend fun flags(id: String, rare: Boolean, review: Boolean, note: String) = mutate {
        val group = dao.group(id); editable(group); dao.update(group.copy(rare = rare, review = review, note = note.take(1000)))
        event(group.sessionId, id, "FLAGS:rare=$rare,review=$review")
    }
    suspend fun barcode(id: String, value: String?) = mutate {
        val group = dao.group(id); editable(group)
        val raw = value?.trim()
        if (!raw.isNullOrEmpty()) require(raw.length in listOf(8, 12, 13, 14) && (Barcodes.checksum(raw) || (raw.length == 8 && Barcodes.candidate(raw, "UPC_E").valid))) { "Enter a valid UPC/EAN with its check digit, or leave blank for no barcode." }
        dao.update(group.copy(barcodeOverride = raw)); event(group.sessionId, id, "BARCODE_CORRECTED")
    }
    suspend fun deletePhoto(id: String) = mutate {
        val photo = dao.photo(id); val group = dao.group(photo.groupId); editable(group)
        dao.update(photo.copy(deleted = true)); event(group.sessionId, group.id, "PHOTO_REMOVED:$id")
    }
    suspend fun restorePhoto(id: String) = mutate {
        val p = dao.photo(id); editable(dao.group(p.groupId)); dao.update(p.copy(deleted = false, position = (dao.photos(p.groupId).maxOfOrNull { it.position } ?: -1) + 1))
    }
    suspend fun role(id: String, role: Role) = mutate {
        val photo = dao.photo(id); editable(dao.group(photo.groupId)); dao.update(photo.copy(role = role.name))
    }
    suspend fun reorder(id: String, delta: Int) = mutate {
        val photo = dao.photo(id); editable(dao.group(photo.groupId))
        val photos = dao.photos(photo.groupId).filter { !it.deleted }.toMutableList(); val index = photos.indexOfFirst { it.id == id }
        require(index + delta in photos.indices) { "Already at the end." }
        java.util.Collections.swap(photos, index, index + delta)
        photos.forEachIndexed { i, p -> dao.update(p.copy(position = i)) }
    }
    suspend fun move(id: String, delta: Int) = mutate {
        val photo = dao.photo(id); val source = dao.group(photo.groupId); editable(source)
        val target = dao.groups(source.sessionId).firstOrNull { it.number == source.number + delta } ?: error("No neighboring group.")
        editable(target)
        dao.update(photo.copy(groupId = target.id, position = (dao.photos(target.id).maxOfOrNull { it.position } ?: -1) + 1))
        event(source.sessionId, source.id, "PHOTO_MOVED:$id:${target.id}")
    }
    fun archiveLimit(): Long = preferences.getInt("archiveMb", 500).toLong() * 1_000_000
    suspend fun queueArchives(sessionId: String, automatic: Boolean = false) {
        val jobs = mutate {
            check(dao.pending().isEmpty()) { "Wait for the photograph to save." }
            val session = dao.session(sessionId)
            val groups = dao.groups(sessionId).filter { it.closed && it.archiveId == null }
                .filter { g -> dao.photos(g.id).any { !it.deleted && it.state == "READY" } }
            groups.forEach { g -> check(dao.photos(g.id).none { !it.deleted && it.state != "READY" }) { "Resolve interrupted captures first." } }
            val sizes = groups.map { g -> GroupSize(g.id, dao.photos(g.id).filter { !it.deleted }.sumOf { it.bytes }) }
            var part = (dao.archives(sessionId).maxOfOrNull { it.part } ?: 0) + 1
            val planned = ArchivePlanner.parts(sizes, archiveLimit())
            val complete = if (automatic && planned.lastOrNull()?.sumOf { it.bytes }?.let { it < archiveLimit() } == true) planned.dropLast(1) else planned
            complete.map { chunk ->
                val number = part++
                val slug = session.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                val date = session.createdAt.take(10)
                val archive = ArchiveRow(sessionId = sessionId, part = number, filename = "CVHS_${date}_${slug}_${session.id.take(8)}_PART%03d.zip".format(number), groupIds = JSONArray(chunk.map { it.id }).toString(), targetBytes = archiveLimit())
                dao.insert(archive)
                chunk.forEach { g -> dao.update(dao.group(g.id).copy(archiveId = archive.id)) }; archive
            }
        }
        jobs.forEach { enqueueArchive(it.id) }
    }
    fun enqueueArchive(id: String) { WorkManager.getInstance(context).enqueueUniqueWork("archive-$id", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ArchiveWorker>().setInputData(workDataOf("id" to id)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()) }
    fun enqueueScan(photo: PhotoRow) {
        if (photo.scanState == "PENDING") WorkManager.getInstance(context).enqueueUniqueWork("scan-${photo.id}", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BarcodeWorker>().setInputData(workDataOf("id" to photo.id)).build())
    }
    suspend fun rescan(id: String) = mutate {
        val p = dao.photo(id); editable(dao.group(p.groupId)); val updated = p.copy(scanState = "PENDING"); dao.update(updated); enqueueScan(updated)
    }
}

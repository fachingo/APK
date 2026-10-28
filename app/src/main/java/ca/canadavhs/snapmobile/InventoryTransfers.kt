package ca.canadavhs.snapmobile

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val count=read(buffer); if(count<0) break; require(output.size().toLong()+count<=limit) { "Metadata exceeds package limit." }; output.write(buffer,0,count) }
    return output.toByteArray()
}
internal object TransferProcess { val active = ConcurrentHashMap.newKeySet<String>() }
private val BACKUP_MAGIC = "SNAPIMS\n".toByteArray(Charsets.US_ASCII)
private const val BACKUP_ITERATIONS = 310_000
private const val BACKUP_HEADER_BYTES = 44

private fun backupKey(password: CharArray, salt: ByteArray): SecretKeySpec {
    require(password.size in 12..256) { "Use a backup passphrase of 12–256 characters. Keep it safe; it cannot be recovered." }
    val specification = PBEKeySpec(password,salt,BACKUP_ITERATIONS,256)
    return try { val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        try { SecretKeySpec(bytes,"AES") } finally { bytes.fill(0) }
    } finally { specification.clearPassword() }
}
private fun backupHeader(salt: ByteArray, nonce: ByteArray): ByteArray = ByteArrayOutputStream().apply {
    DataOutputStream(this).apply { write(BACKUP_MAGIC); writeInt(1); writeInt(BACKUP_ITERATIONS); write(salt); write(nonce) }
}.toByteArray()
internal suspend fun copyCancellable(input: InputStream, output: OutputStream, limit: Long): Long {
    var count = 0L; val buffer = ByteArray(262144)
    while(true) { currentCoroutineContext().ensureActive(); val size = input.read(buffer); if(size<0) break
        count = Math.addExact(count,size.toLong()); check(count <= limit) { "File exceeds the supported package size." }; output.write(buffer,0,size)
    }; return count
}
internal suspend fun encryptBackup(password: CharArray, target: File, writeZip: suspend (OutputStream) -> Unit) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }; val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    try {
        val header = backupHeader(salt,nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,backupKey(password,salt),GCMParameterSpec(128,nonce)); updateAAD(header) }
        FileOutputStream(target).use { file -> file.write(header); CipherOutputStream(file,cipher).use { writeZip(it) } }
        FileOutputStream(target,true).use { it.fd.sync() }
    } finally { password.fill('\u0000') }
}
internal suspend fun decryptBackup(password: CharArray, source: File, zip: File) {
    try {
        source.inputStream().use { input ->
            val header = ByteArray(BACKUP_HEADER_BYTES).also { DataInputStream(input).readFully(it) }; require(header.size == BACKUP_HEADER_BYTES) { "Incomplete backup header." }
            val parsed = DataInputStream(ByteArrayInputStream(header)); val magic = ByteArray(8).also { parsed.readFully(it) }
            require(magic.contentEquals(BACKUP_MAGIC) && parsed.readInt()==1 && parsed.readInt()==BACKUP_ITERATIONS) { "Unsupported backup format." }
            val salt = ByteArray(16).also { parsed.readFully(it) }; val nonce = ByteArray(12).also { parsed.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,backupKey(password,salt),GCMParameterSpec(128,nonce)); updateAAD(header) }
            try { CipherInputStream(input,cipher).use { decoded -> FileOutputStream(zip).use { out -> copyCancellable(decoded,out,MAX_PACKAGE_BYTES); out.fd.sync() } } }
            catch(e: CancellationException) { throw e }
            catch (_: Exception) { error("Backup could not be authenticated. Check the passphrase and file; inventory is unchanged.") }
        }
    } finally { password.fill('\u0000') }
}
suspend fun CaptureRepository.recoverTransferJobs() = withContext(Dispatchers.IO) {
    for(row in journal.inventoryDao().unfinished()) if(row.id !in TransferProcess.active) {
        journal.inventoryDao().update(row.copy(state="INTERRUPTED",error="Android/app stopped this operation. Review the inventory and retry; no operation is still running.",updatedAt=java.time.OffsetDateTime.now().toString()))
    }
}
internal suspend fun <T> CaptureRepository.transferJob(kind: String, suffix: String, block: suspend (TransferJobRow, File) -> T): T = withContext(Dispatchers.IO) {
    val initial = TransferJobRow(kind=kind)
    val row = initial.copy(filename="SnapIMS_${kind}_${initial.id}$suffix")
    val jobs = journal.inventoryDao(); jobs.insert(row); TransferProcess.active.add(row.id)
    val stage = File(context.cacheDir,"inventory-transfer-${row.id}").apply { check(mkdirs()) }
    try {
        val result = block(row,stage)
        val latest = jobs.job(row.id) ?: row
        jobs.update(latest.copy(state="SUCCEEDED",error="",updatedAt=java.time.OffsetDateTime.now().toString()))
        result
    } catch(e: Exception) {
        withContext(NonCancellable) {
            val latest = jobs.job(row.id) ?: row
            jobs.update(latest.copy(state=if(e is CancellationException) "CANCELLED" else "ERROR",error=if(e is CancellationException) "Operation cancelled. Existing originals and inventory retained." else (e.message ?: "Transfer failed; existing data retained.").take(500),updatedAt=java.time.OffsetDateTime.now().toString()))
        }; throw e
    } finally { TransferProcess.active.remove(row.id); stage.deleteRecursively() }
}
suspend fun CaptureRepository.createInventoryBackup(password: CharArray): File = transferJob("BACKUP",".snapbak") { row, stage ->
    try {
        val snapshot = hashSnapshotFiles(buildSnapshot())
        val files = snapshot.getJSONArray("files"); val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
        check(freeSpace(stage) > total + 30_000_000) { "Not enough free space to create a protected backup." }
        val temp = File(stage,"encrypted.pending"); val jobs = journal.inventoryDao()
        jobs.update(row.copy(total=files.length()))
        encryptBackup(password,temp) { output ->
            ZipOutputStream(output).use { zip ->
                val metadata = canonicalJson(snapshot).toByteArray(Charsets.UTF_8)
                check(metadata.size <= MAX_SNAPSHOT_BYTES) { "Export separate batches: metadata exceeds the snapshot limit." }
                zip.putNextEntry(ZipEntry("snapshot.json")); zip.write(metadata); zip.closeEntry()
                for(i in 0 until files.length()) {
                    currentCoroutineContext().ensureActive()
                    val record = files.getJSONObject(i); val path = record.getString("path"); val source = snapshotFile(path)
                    check(source.length()==record.getLong("bytes") && hashFile(source)==record.getString("sha256")) { "A source file changed; backup stopped." }
                    zip.putNextEntry(ZipEntry(path)); source.inputStream().use { check(copyCancellable(it,zip,record.getLong("bytes"))==record.getLong("bytes")) }; zip.closeEntry()
                    if(i%5==0 || i==files.length()-1) jobs.update(row.copy(total=files.length(),completed=i+1))
                }
            }
        }
        val target = File(archiveDir,row.filename); atomicMove(temp,target); sync(archiveDir)
        jobs.update(row.copy(total=files.length(),completed=files.length(),sha256=hashFile(target)))
        target
    } finally { password.fill('\u0000') }
}
suspend fun CaptureRepository.restoreInventoryBackup(source: File, password: CharArray): Int = transferJob("RESTORE",".snapbak") { row,stage ->
    try {
        val zip = File(stage,"authenticated.zip"); decryptBackup(password,source,zip)
        val snapshot = stageSnapshot(zip,File(stage,"files").apply { mkdirs() })
        val count = mergeSnapshot(snapshot,File(stage,"files"))
        journal.inventoryDao().update(row.copy(completed=count,total=count))
        count
    } finally { password.fill('\u0000') }
}

package ca.canadavhs.snapmobile.core

import java.security.MessageDigest
import java.util.UUID

enum class Role { FRONT, BACK, ADDITIONAL }
fun defaultRole(index: Int): Role = when (index) { 0 -> Role.FRONT; 1 -> Role.BACK; else -> Role.ADDITIONAL }
fun validLocation(value: String): Boolean = value.isEmpty() || Regex("Q1|[A-J]([1-9]|10)").matches(value)
fun newId(): String = UUID.randomUUID().toString()
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class BarcodeCandidate(val raw: String, val format: String, val normalized: String, val valid: Boolean)
object Barcodes {
    // UPC-E expands to UPC-A before checksum validation; preserve raw and symbology.
    fun candidate(raw: String, format: String): BarcodeCandidate {
        val expanded = if (format == "UPC_E") expandUpce(raw) else raw
        val expected = mapOf("UPC_A" to 12, "UPC_E" to 8, "EAN_13" to 13, "EAN_8" to 8)[format]
        val valid = if (expected == null) raw.isNotBlank() else raw.length == expected &&
            expanded != null && expanded.all(Char::isDigit) && checksum(expanded)
        val normalized = if (valid && expanded != null && format in setOf("UPC_A", "UPC_E", "EAN_13", "EAN_8"))
            expanded.padStart(14, '0') else raw
        return BarcodeCandidate(raw, format, normalized, valid)
    }
    fun checksum(value: String): Boolean {
        if (value.length < 2 || !value.all { it in '0'..'9' }) return false
        val sum = value.dropLast(1).reversed().mapIndexed { index, c -> c.digitToInt() * if (index % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == value.last().digitToInt()
    }
    fun expandUpce(raw: String): String? {
        if (raw.length != 8 || !raw.all { it in '0'..'9' } || raw[0] !in "01") return null
        val d = raw.substring(1, 7)
        val body = when (d[5]) {
            '0', '1', '2' -> "${d.take(2)}${d[5]}0000${d.substring(2, 5)}"
            '3' -> "${d.take(3)}00000${d.substring(3, 5)}"
            '4' -> "${d.take(4)}00000${d[4]}"
            else -> "${d.take(5)}0000${d[5]}"
        }
        return "${raw[0]}$body${raw.last()}"
    }
    fun status(candidates: List<BarcodeCandidate>): String = when (candidates.filter { it.valid }.map { it.normalized }.distinct().size) {
        0 -> "NO_BARCODE"; 1 -> "FOUND"; else -> "AMBIGUOUS"
    }
}

data class GroupSize(val id: String, val bytes: Long)
object ArchivePlanner {
    /** Whole groups only. A single oversized group is emitted alone. */
    fun parts(groups: List<GroupSize>, limit: Long): List<List<GroupSize>> {
        require(limit > 0 && groups.all { it.bytes >= 0 })
        val result = mutableListOf<List<GroupSize>>()
        var current = mutableListOf<GroupSize>(); var bytes = 0L
        groups.forEach { group ->
            if (current.isNotEmpty() && group.bytes > limit - bytes) {
                result += current; current = mutableListOf(); bytes = 0
            }
            current += group; bytes = Math.addExact(bytes, group.bytes)
            if (bytes >= limit) { result += current; current = mutableListOf(); bytes = 0 }
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}

fun photoFilename(timestamp: String, barcode: String?, groupId: String, index: Int, role: Role): String {
    val time = timestamp.replace(Regex("[^0-9]"), "").take(14)
    val code = barcode?.filter { it in '0'..'9' }?.take(14)?.ifBlank { null } ?: "NOUPC"
    val suffix = when (role) { Role.FRONT -> "F"; Role.BACK -> "B"; else -> "%02d".format(index + 1) }
    // Ordinal prevents collisions with repeated or edited roles; full UUID is identity.
    return "${time}_${code}_${groupId}_${suffix}_${index + 1}.jpg"
}

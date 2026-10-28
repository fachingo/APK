package ca.canadavhs.snapmobile.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Ports of SnapIMS 0.15.0 money.py, inventory.py, manifests.py and protocol.py. */
object InventoryRules {
    val conditions = listOf("Not Graded", "Fair", "Good", "Very Good", "Like New", "Sealed", "Damaged", "Mold Review")
    const val maxPriceCents = 100_000_000L
    fun cents(text: String): Long? {
        if (text.isBlank()) return null
        val amount = text.trim().replace("CAD", "").replace("$", "").replace(",", "").trim()
        val value = try { BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }
            catch (_: Exception) { throw IllegalArgumentException("Enter a valid currency amount.") }
        require(value in 0..maxPriceCents) { "Price must be between 0 and CAD 1,000,000." }
        return value
    }
    fun discountBasisPoints(text: String): Int {
        val value = try { BigDecimal(text.ifBlank { "0" }).setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact() }
            catch (_: Exception) { throw IllegalArgumentException("Enter a valid discount percentage.") }
        require(value in 0..10000) { "Discount must be between 0 and 100 percent." }; return value
    }
    fun priceText(cents: Long?): String = cents?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: ""
    fun discounted(cents: Long, basisPoints: Int): Long {
        require(cents in 0..maxPriceCents && basisPoints in 0..10000)
        return BigDecimal(cents).multiply(BigDecimal(10000 - basisPoints)).divide(BigDecimal(10000), 0, RoundingMode.HALF_UP).longValueExact()
    }
    fun batchId(createdAt: String, sessionId: String): String = OffsetDateTime.parse(createdAt).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-M" + sessionId.replace("-", "").take(12)
    fun itemId(batchId: String, location: String, sequence: Int): String {
        require(sequence > 0); return "$batchId-$location-${sequence.toString().padStart(3, '0')}"
    }
    fun photoName(itemId: String, order: Int): String {
        require(order > 0); return "$itemId-${if (order == 1) "F" else order.toString().padStart(2, '0')}.jpg"
    }
    fun normalizedTitle(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT).replace("ß","ss").replace("ς","σ").replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .trim().split(Regex("\\s+")).filter { it !in setOf("vhs", "tape", "movie", "video", "cassette") }.joinToString(" ")
    fun validInventoryLocation(text: String): Boolean = text.length <= 120 && text.none { it.code < 32 }
    fun validation(title: String, price: Long?, discount: Int, quantity: Int, condition: String,
        poolMode: String, location: String, barcode: String, itemId: String, sku: String, photos: Int, front: Boolean): List<String> = buildList {
        if (title.isBlank()) add("Title is required")
        if (title.length > 255) add("Title must be 255 characters or fewer")
        if (price == null || price <= 0 || price > maxPriceCents) add("Price must be greater than zero and within the supported range")
        if (discount !in 0..10000) add("Discount must be between 0 and 100 percent")
        if (quantity < 0) add("Quantity cannot be negative")
        if (condition !in conditions) add("Choose a supported condition")
        if (poolMode !in setOf("POOLED", "UNIQUE")) add("Pool mode must be POOLED or UNIQUE")
        if (!validInventoryLocation(location)) add("Location must be at most 120 characters without control characters")
        if (barcode.isNotBlank() && (barcode.length !in listOf(8,12,13,14) || !barcode.all { it in '0'..'9' })) add("Barcode must be 8, 12, 13, or 14 digits")
        if (sku != itemId) add("SKU must remain equal to immutable Item ID")
        if (photos == 0) add("At least one product image is required") else if (!front) add("Front image F is missing")
    }
}

data class CaptureCommand(val kind: String, val value: String = "")
fun parseCaptureCommand(payload: String): CaptureCommand? {
    val normalized = payload.trim().uppercase(java.util.Locale.ROOT)
    val fixed = mapOf("CVHS1:BATCH:START" to "START", "CVHS1:BATCH:END" to "END", "CVHS1:ITEM:NEXT" to "NEXT", "CVHS1:ITEM:CONT" to "CONT", "CVHS1:FLAG:RARE" to "RARE", "CVHS1:FLAG:REVIEW" to "REVIEW")
    fixed[normalized]?.let { return CaptureCommand(it) }
    if (normalized.startsWith("CVHS1:LOC:") && validLocation(normalized.removePrefix("CVHS1:LOC:")) && normalized.length > 10)
        return CaptureCommand("LOCATION", normalized.removePrefix("CVHS1:LOC:"))
    return null
}

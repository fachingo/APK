package ca.canadavhs.snapmobile.core

import org.junit.Test
import kotlin.test.*

class CaptureRulesTest {
    @Test fun frontBackAdditional() { assertEquals(listOf(Role.FRONT, Role.BACK, Role.ADDITIONAL, Role.ADDITIONAL), (0..3).map(::defaultRole)) }
    @Test fun normalizeLeadingZeroWithoutLosingRaw() {
        val upc = Barcodes.candidate("036000291452", "UPC_A")
        val ean = Barcodes.candidate("0036000291452", "EAN_13")
        assertTrue(upc.valid); assertTrue(ean.valid); assertEquals(upc.normalized, ean.normalized)
        assertEquals("036000291452", upc.raw)
    }
    @Test fun upceExpansion() { assertEquals("042100005264", Barcodes.expandUpce("04252614")); assertTrue(Barcodes.candidate("04252614", "UPC_E").valid) }
    @Test fun ambiguousAndMissing() {
        assertEquals("NO_BARCODE", Barcodes.status(emptyList()))
        assertEquals("NO_BARCODE", Barcodes.status(listOf(Barcodes.candidate("036000291453", "UPC_A"))))
        assertEquals("AMBIGUOUS", Barcodes.status(listOf(Barcodes.candidate("036000291452", "UPC_A"), Barcodes.candidate("012345678905", "UPC_A"))))
        assertEquals("FOUND", Barcodes.status(listOf(Barcodes.candidate("036000291452", "UPC_A"), Barcodes.candidate("0036000291452", "EAN_13"))))
    }
    @Test fun separatePhysicalCopiesMayShareBarcode() { val first = newId(); val second = newId(); assertNotEquals(first, second); assertNotEquals(photoFilename("2026-10-27T20:00:00Z", "036000291452", first, 0, Role.FRONT), photoFilename("2026-10-27T20:00:00Z", "036000291452", second, 0, Role.FRONT)) }
    @Test fun rolloverAt500MbWholeProducts() {
        val parts = ArchivePlanner.parts(listOf(GroupSize("a", 250_000_000), GroupSize("b", 249_000_000), GroupSize("c", 3_000_000), GroupSize("d", 600_000_000)), 500_000_000)
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), parts.map { p -> p.map { it.id } })
    }
    @Test fun exactBoundaryAndEmpty() { assertTrue(ArchivePlanner.parts(emptyList(), 500).isEmpty()); assertEquals(2, ArchivePlanner.parts(listOf(GroupSize("a", 500), GroupSize("b", 1)), 500).size) }
    @Test fun synthetic100Products300Photos() {
        val groups = (1..100).map { GroupSize(newId(), 3 * 4_000_000) }
        val parts = ArchivePlanner.parts(groups, 500_000_000)
        assertEquals(groups, parts.flatten()); assertEquals(100, parts.flatten().map { it.id }.toSet().size)
        assertTrue(parts.all { it.sumOf { g -> g.bytes } <= 500_000_000 }); assertEquals(3, parts.size)
    }
    @Test fun locationVocabulary() { assertTrue(validLocation("Q1")); assertTrue(validLocation("J10")); assertTrue(validLocation("")); assertFalse(validLocation("K1")); assertFalse(validLocation("A0")) }
    @Test fun duplicateRolesCannotCollide() { val id = newId(); assertNotEquals(photoFilename("2026-10-27T00:00:00Z", null, id, 0, Role.FRONT), photoFilename("2026-10-27T00:00:00Z", null, id, 1, Role.FRONT)) }
}

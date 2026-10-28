package ca.canadavhs.snapmobile.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class DesktopConformanceTest {
    private val fixtures=JSONObject(javaClass.getResourceAsStream("/desktop_conformance.json")!!.bufferedReader().use {it.readText()})
    @Test fun moneyMatchesExistingDesktopFixturesExactly() {
        val values=fixtures.getJSONArray("money")
        for(i in 0 until values.length()) {val row=values.getJSONObject(i)
            val cents=InventoryRules.cents(row.getString("amount"))!!;val discount=InventoryRules.discountBasisPoints(row.getString("discount"))
            assertEquals(row.getLong("cents"),cents);assertEquals(row.getInt("basis_points"),discount);assertEquals(row.getLong("final"),InventoryRules.discounted(cents,discount))
        }
        for(value in listOf("-0.01","NaN","1e100","1000000.01")) assertFailsWith<IllegalArgumentException> {InventoryRules.cents(value)}
        assertNull(InventoryRules.cents(""));assertFailsWith<IllegalArgumentException> {InventoryRules.discountBasisPoints("100.01")}
    }
    @Test fun idsAndPhotoNamesMatchDesktopWithoutBarcodeIdentity() {
        val rows=fixtures.getJSONArray("identities")
        for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val id=InventoryRules.itemId(row.getString("batch"),row.getString("location"),row.getInt("sequence"))
            assertEquals(row.getString("item_id"),id)
            repeat(3) {n->assertEquals(row.getJSONArray("photos").getString(n),InventoryRules.photoName(id,n+1))}
        }
        assertNotEquals(InventoryRules.itemId("B","A1",1),InventoryRules.itemId("B","A1",2))
    }
    @Test fun conditionsAndQrVocabularyMatchSourceFixtures() {
        val conditions=fixtures.getJSONArray("conditions");assertEquals((0 until conditions.length()).map {conditions.getString(it)},InventoryRules.conditions)
        val kinds=mapOf("batch_start" to "START","batch_end" to "END","item_next" to "NEXT","item_cont" to "CONT","flag_rare" to "RARE","flag_review" to "REVIEW","location" to "LOCATION")
        val rows=fixtures.getJSONArray("commands")
        for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val actual=parseCaptureCommand(row.getString("payload"))
            if(row.isNull("kind")) assertNull(actual) else {assertEquals(kinds[row.getString("kind")],actual?.kind);if(!row.isNull("value")) assertEquals(row.getString("value"),actual?.value)}
        }
    }
    @Test fun realQrRasterDecodesOnlySupportedCommands() {
        fun pixels(value:String):IntArray {val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,400,400,mapOf(EncodeHintType.MARGIN to 4));return IntArray(160000) {if(matrix[it%400,it/400]) 0xff000000.toInt() else -1}}
        assertEquals(listOf(CaptureCommand("NEXT")),CommandQrDecoder.decode(400,400,pixels("CVHS1:ITEM:NEXT")))
        assertTrue(CommandQrDecoder.decode(400,400,pixels("https://example.com/customer-link")).isEmpty())
    }
}

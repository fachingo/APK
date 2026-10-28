package ca.canadavhs.snapmobile.core

import org.junit.Test
import org.junit.Assert.*

class MarketCsvTest {
    @Test fun readsQuotedNewlinesEscapedQuotesBomAndTrailingEmptyFieldsWithoutSplittingRecords() {
        MarketCsv("\uFEFFtitle,id,note\r\n\"Alien, \"\"sealed\"\"\",001,\"first\nsecond\"\r\nOther,002,\n".reader()).use {reader->assertEquals(listOf("title","id","note"),reader.next());assertEquals(listOf("Alien, \"sealed\"","001","first\nsecond"),reader.next());assertEquals(listOf("Other","002",""),reader.next());assertNull(reader.next())}
    }
    @Test fun malformedQuotingAndOversizedFieldsAreRejected() {
        for(value in listOf("\"unclosed","abc\"def,1","\"closed\"suffix,1","x".repeat(200001))) {
            try {MarketCsv(value.reader()).use {it.next()};fail()} catch(_:IllegalArgumentException) {}
        }
    }
}

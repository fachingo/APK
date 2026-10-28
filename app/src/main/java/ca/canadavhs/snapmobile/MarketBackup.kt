package ca.canadavhs.snapmobile

import android.database.sqlite.SQLiteDatabase
import ca.canadavhs.snapmobile.core.newId
import kotlinx.coroutines.*
import java.io.File

/** Stream evidence into a consistent portable SQLite member while the shared DB transaction is held. */
internal suspend fun CaptureRepository.exportMarketArchiveLocked():File? {
    val db=journal.openHelper.writableDatabase
    val count=db.query("SELECT COUNT(*) FROM market_sales").use {it.moveToFirst();it.getLong(0)}
    if(count==0L) return null
    val dir=File(dataRoot,"market-exports").apply {mkdirs()};val target=File(dir,"${newId()}.sqlite")
    try {
        SQLiteDatabase.openOrCreateDatabase(target,null).use {output->
            output.execSQL(marketArchiveSchema);output.execSQL("CREATE TABLE market_format(version INTEGER NOT NULL,format TEXT NOT NULL)");output.execSQL("INSERT INTO market_format VALUES(1,'snapims.market.archive')")
            output.beginTransaction();try {db.query("SELECT * FROM market_sales ORDER BY sourceKey,observedAt,id").use {c->while(c.moveToNext()) {currentCoroutineContext().ensureActive();output.insertOrThrow("market_records",null,marketValues(cursorObject(c)))}};output.setTransactionSuccessful()} finally {output.endTransaction()}
            output.rawQuery("PRAGMA quick_check",null).use {check(it.moveToFirst() && it.getString(0)=="ok")}
        }
        java.io.FileOutputStream(target,true).use {it.fd.sync()};sync(dir);return target
    } catch(e:Exception) {target.delete();throw e}
}
internal suspend fun CaptureRepository.exportMarketDatabase():File {marketBusy.value=true;try {return mutate {exportMarketArchiveLocked() ?: error("Import sold records before exporting the market database.")}} finally {marketBusy.value=false}}

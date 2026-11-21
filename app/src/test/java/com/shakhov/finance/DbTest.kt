package com.shakhov.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun ts(d: Int, m: Int, h: Int = 10, min: Int = 0) =
        Calendar.getInstance().apply { set(2026, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun everything() {
        Db.get(ctx).importJson(org.json.JSONObject().put("tx", org.json.JSONArray()))
        Prefs.get(ctx).edit().putBoolean("seeded", false).apply()
        Seed.run(ctx)
        val db = Db.get(ctx)
        val hasSeed = runCatching { ctx.assets.open("seed.json").close() }.isSuccess
        assertEquals(if (hasSeed) 60 else 0, db.count())
        db.setRule(Parsers.merchantKey("GOOZA"), "", "Табак")
        // known merchant from old table → category + Russian name via rule
        val cal = "היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקה ב-20/11 בGOOZA בסך 140.00 שח."
        assertNotNull(Ingest.handle(ctx, cal, ts(20, 11, 18), Src.SMS, "CAL"))
        val t = db.query("merchant='GOOZA' AND ts>?", arrayOf(ts(19, 11).toString())).single()
        assertEquals("Табак", t.category)
        // the same purchase from Google Wallet push → merged, not duplicated
        assertNull(Ingest.handle(ctx, "₪140.00 with Visa ••1234", ts(20, 11, 18, 2), Src.NOTIF_WALLET, NotifService.WALLET, "GOOZA"))
        assertEquals(1, db.query("amount=140 AND ts>?", arrayOf(ts(19, 11).toString())).size)
        assertEquals(Src.SMS or Src.NOTIF_WALLET, db.query("amount=140 AND ts>?", arrayOf(ts(19, 11).toString())).single().src)
        // same SMS delivered twice (receiver + history import) → ignored
        assertNull(Ingest.handle(ctx, cal, ts(20, 11, 18), Src.SMS, "CAL"))
        // two genuine identical purchases (vending machine) → two records
        val v = "היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקה ב-20/11 בנאייקס ישראל בסך 7.00 שח."
        Ingest.handle(ctx, v, ts(20, 11, 9), Src.SMS, "CAL"); Ingest.handle(ctx, v, ts(20, 11, 13), Src.SMS, "CAL")
        assertEquals(2, db.query("amount=7 AND ts>?", arrayOf(ts(19, 11).toString())).size)
        // bank salary + balance
        Ingest.handle(ctx, "♦️ 12-345:\nבוצע זיכוי משכורת בסך\n9,876.54 שח", ts(9, 11, 23), Src.SMS, "Poalim")
        assertEquals("Зарплата", db.query("amount>9000").single().category)
        Ingest.handle(ctx, "♦️ 12-345\nיתרת חשבונך: 10,250.50 ש\"ח,", ts(15, 11), Src.SMS, "Poalim")
        assertEquals(10250.50, db.balances().single().second, 0.001)
        // receipt split into categories, then the card SMS arrives → merges into the group
        runBlocking {
            saveReceipt(ctx, listOf(RItem("לחם", "хлеб", "10", "Продукты"), RItem("יין", "вино", "40", "Алкоголь")), 55.0, "שופרסל", 0)
        }
        val g = db.query("grp!=''")
        assertEquals(2, g.size); assertEquals(55.0, g.sumOf { it.amount }, 0.001)
        assertNull(Ingest.handle(ctx, "היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקה בשופרסל בסך 55.00 שח.", System.currentTimeMillis(), Src.SMS, "CAL"))
        assertTrue(db.query("grp!=''").all { it.src and Src.SMS != 0 })
        // Cal statement table: row equal to GOOZA SMS merges; new row added
        val rows = Importers.table(listOf(
            listOf("כרטיס ויזה המסתיים ב-1234"),
            listOf("תאריך עסקה", "שם בית העסק", "סכום עסקה", "סכום חיוב", "הערות"),
            listOf("20/11/2026", "GOOZA", "140.00", "140.00", ""),
            listOf("21/11/2026", "ארומה נוף הגליל", "₪ 32.00", "32.00", ""),
            listOf("05/11/2026", "KSP נצרת", "2,579.00", "286.56", "תשלום 2 מתוך 9")
        ))
        assertEquals(3, rows.size); assertEquals("1234", rows[0].card); assertEquals(9, rows[2].installments)
        val n = Importers.commit(ctx, rows)
        assertEquals(2, n)
        assertEquals(1, db.query("amount=140 AND ts>?", arrayOf(ts(19, 11).toString())).size)
        assertEquals("Кафе и рестораны", db.query("amount=32").single().category)
        // backup roundtrip
        val before = db.count()
        val json = db.exportJson(); db.importJson(json)
        assertEquals(before, db.count())
    }

    @Test fun pdfBalanceLogic() {
        val text = """
            01/11/2026 משכורת 9,876.54 20,000.00
            03/11/2026 כאל 1,800.00 17,500.00
            05/11/2026 העברה 100.00 17,400.00
        """.trimIndent()
        val r = Importers.pdfText(text)
        assertEquals(3, r.size)
        assertEquals(Types.TRANSFER, r[1].type)
        assertEquals(Types.EXPENSE, r[2].type)
    }
}

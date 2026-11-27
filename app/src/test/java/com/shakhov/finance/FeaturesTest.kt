package com.shakhov.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeaturesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun names() {
        assertEquals("Эйс (товары для дома), Назарет", Dict.merchantName(ctx, "אייס נצרת"))
        assertTrue(Dict.merchantName(ctx, "להב נצרת עילית")!!.startsWith("Лахав"))
        assertNull(Dict.merchantName(ctx, "דקטרי"))
        // short key matches only a whole word
        assertNull(Dict.merchantName(ctx, "פזורה"))
    }

    @Test fun unparsedBackupAndIgnore() {
        val db = Db.get(ctx)
        db.importJson(org.json.JSONObject().put("tx", org.json.JSONArray()))
        assertNull(Ingest.handle(ctx, "יתרה בכרטיס: 120.50 ₪ נכון ל-01/12", 1000, Src.SMS, "X"))
        assertEquals(1, db.unparsed().size)
        db.importJson(db.exportJson())
        assertEquals(1, db.unparsed().size)
        Prefs.ignore(ctx, db.unparsed().first().text)
        db.deleteUnparsed(db.unparsed().first().id)
        Ingest.handle(ctx, "יתרה בכרטיס: 77.00 ₪ נכון ל-02/12", 2000, Src.SMS, "X")
        assertEquals(0, db.unparsed().size)
    }

    @Test fun recon() {
        val db = Db.get(ctx)
        db.importJson(org.json.JSONObject().put("tx", org.json.JSONArray()))
        val now = System.currentTimeMillis()
        val d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        val mo = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        Ingest.handle(ctx, "בוצעה עסקה\nב-סטופ בסך ₪55 בכרטיס ויזה 1234, פרטים", now - 3 * 3600000, Src.NOTIF_CAL, "cal", "")
        Ingest.handle(ctx, "בוצעה עסקה\nב-טסט פיקס בסך ₪80 בכרטיס ויזה 1234, פרטים", now - 2 * 3600000, Src.NOTIF_CAL, "cal", "")
        val day = dayStart(now) + 12 * 3600000L
        val r = Importers.commitWithReport(ctx, listOf(
            ImportRow(day, 55.0, Types.EXPENSE, "סטופ", "", card = "1234"),
            ImportRow(day, 30.0, Types.EXPENSE, "FACEBK *X", "", card = "1234")
        ))
        assertEquals(2, r.rows); assertEquals(85.0, r.statementSum, 0.001)
        assertEquals(1, r.added.size); assertEquals("FACEBK *X", r.added.single().merchant)
        assertEquals(listOf(80.0), r.onlyApp.map { it.amount })
        assertTrue(d > 0 && mo > 0)
    }
}

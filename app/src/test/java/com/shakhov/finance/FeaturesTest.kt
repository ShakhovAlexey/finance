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
        val y = dayStart(now) - Db.DAY  // yesterday, fixed hours — independent of the time the test runs
        Ingest.handle(ctx, "בוצעה עסקה\nב-סטופ בסך ₪55 בכרטיס ויזה 1234, פרטים", y + 10 * 3600000L, Src.NOTIF_CAL, "cal", "")
        Ingest.handle(ctx, "בוצעה עסקה\nב-טסט פיקס בסך ₪80 בכרטיס ויזה 1234, פרטים", y + 11 * 3600000L, Src.NOTIF_CAL, "cal", "")
        val day = y + 12 * 3600000L
        val r = Importers.commitWithReport(ctx, listOf(
            ImportRow(day, 55.0, Types.EXPENSE, "סטופ", "", card = "1234"),
            ImportRow(day, 30.0, Types.EXPENSE, "FACEBK *X", "", card = "1234")
        ))
        assertEquals(2, r.rows); assertEquals(85.0, r.statementSum, 0.001)
        assertEquals(1, r.added.size); assertEquals("FACEBK *X", r.added.single().merchant)
        assertEquals(listOf(80.0), r.onlyApp.map { it.amount })
        assertTrue(d > 0 && mo > 0)
    }

    private fun reset() { Db.get(ctx).importJson(org.json.JSONObject().put("tx", org.json.JSONArray())); Prefs.get(ctx).edit().clear().apply(); Cats.load(ctx) }

    @Test fun splitAndStatementMerge() {
        reset(); val db = Db.get(ctx)
        val y = dayStart(System.currentTimeMillis()) - Db.DAY
        Ingest.handle(ctx, "בוצעה עסקה\nב-שופרסל בסך ₪200 בכרטיס ויזה 1234, פרטים", y + 10 * 3600000L, Src.NOTIF_CAL, "cal", "")
        val t = db.query("amount=200").single()
        assertTrue(Split.split(ctx, t, 50.0, "Алкоголь"))
        assertEquals(listOf(50.0, 150.0), db.query("merchant='שופרסל'").map { it.amount }.sorted())
        // the statement line of 200 merges into the split, no new 200
        Importers.commit(ctx, listOf(ImportRow(y + 12 * 3600000L, 200.0, Types.EXPENSE, "שופרסל", "", card = "1234")))
        assertEquals(0, db.query("amount=200").size)
        assertTrue(db.query("merchant='שופרסל'").all { it.src and Src.STATEMENT != 0 })
        // deleting the piece gives the amount back
        Split.delete(ctx, db.query("amount=50").single())
        assertEquals(200.0, db.query("merchant='שופרסל'").single().amount, 0.001)
    }

    @Test fun categoriesAndGoals() {
        reset(); val db = Db.get(ctx)
        assertTrue(Cats.add(ctx, "3D-печать", "🖨️"))
        assertTrue("3D-печать" in Cats.expense); assertEquals("🖨️", Cats.icon("3D-печать"))
        val now = System.currentTimeMillis()
        db.insert(Tx(ts = now, amount = 100.0, category = "3D-печать", src = Src.MANUAL))
        Cats.rename(ctx, "3D-печать", "Принтер", "🖨️")
        assertEquals("Принтер", db.query("amount=100").single().category)
        Cats.delete(ctx, "Принтер")
        assertEquals("Другое", db.query("amount=100").single().category)
        assertFalse("Принтер" in Cats.expense)
        // goal: 10% of income
        db.insert(Tx(ts = now, amount = 10000.0, type = Types.INCOME, category = "Зарплата", src = Src.MANUAL))
        Budget.putGoal(ctx, Budget.Goal("Отпуск", "✈️", 5000.0, 10.0, 0.0))
        assertTrue("Отпуск" in Cats.expense)
        val m = monthStart(now)
        assertEquals(1000.0, Budget.reserved(ctx, m, 10000.0), 0.001)
        Budget.deposit(ctx, Budget.goals(ctx).single(), 1000.0)
        db.insert(Tx(ts = now, amount = 300.0, category = "Отпуск", src = Src.MANUAL))
        assertEquals(700.0, Budget.balance(ctx, "Отпуск"), 0.001)
        assertEquals(1000.0, Budget.reserved(ctx, m, 10000.0), 0.001)
        // settings survive backup/restore
        val full = Backup.full(ctx)
        Prefs.get(ctx).edit().clear().apply(); Cats.load(ctx)
        Prefs.import(ctx, full.getJSONObject("settings"))
        assertEquals("Отпуск", Budget.goals(ctx).single().name)
        assertTrue("Отпуск" in Cats.expense)
    }

    @Test fun limitsRecurringForecast() {
        reset(); val db = Db.get(ctx)
        val m = monthStart(System.currentTimeMillis())
        // HOT 99.9 each of the last 3 months on the 26th, not yet this month
        for (i in 1..3) db.insert(Tx(ts = addMonths(m, -i) + 25 * Db.DAY, amount = 99.9, merchant = "HOT", category = "Подписки", src = Src.STATEMENT))
        // supermarket several times a month — not "recurring"
        for (i in 1..3) for (d in listOf(2, 9)) db.insert(Tx(ts = addMonths(m, -i) + d * Db.DAY, amount = 50.0, merchant = "סטופ", category = "Продукты", src = Src.STATEMENT))
        val rec = Budget.recurring(ctx, m)
        assertEquals(listOf("HOT"), rec.map { it.name }); assertNull(rec.single().paidTs)
        assertEquals(99.9, Budget.forecast(ctx, m).expectedRecurring, 0.001)
        Budget.setLimit(ctx, "Продукты", 100.0)
        val a = Tx(ts = m + Db.DAY, amount = 85.0, category = "Продукты", src = Src.MANUAL); db.insert(a)
        assertNotNull(Budget.limitAlert(ctx, a))
        val b = Tx(ts = m + Db.DAY, amount = 30.0, category = "Продукты", src = Src.MANUAL); db.insert(b)
        assertTrue(Budget.limitAlert(ctx, b)!!.contains("превышен"))
    }
}

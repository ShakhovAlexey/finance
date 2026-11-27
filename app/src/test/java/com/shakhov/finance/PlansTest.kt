package com.shakhov.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlansTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun ts(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { set(y, m - 1, d, h, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { "${it.get(Calendar.DAY_OF_MONTH)}.${it.get(Calendar.MONTH) + 1}" }

    @Test fun math() {
        val p = Plan(name = "KSP", total = 2463.0, count = 9, firstTs = Plans.nextCharge(ts(2026, 6, 2), 10))
        assertEquals(279.0, Plans.payment(p, 1), 0.001)
        assertEquals(273.0, Plans.payment(p, 5), 0.001)
        assertEquals(2463.0, (1..9).sumOf { Plans.payment(p, it) }, 0.001)
        assertEquals("10.6", day(p.firstTs)); assertEquals("10.10", day(Plans.paymentTs(p, 5)))
        assertEquals("10.12", day(Plans.nextCharge(ts(2026, 11, 15), 10)))
        assertEquals("10.11", day(Plans.nextCharge(ts(2026, 11, 5), 10)))
        val ins = Plan(name = "Страховка", monthly = 312.5, count = 12, firstTs = ts(2026, 3, 15), source = Plans.BANK)
        assertEquals(3750.0, Plans.total(ins), 0.001)
    }

    @Test fun flows() {
        val db = Db.get(ctx)
        db.importJson(org.json.JSONObject().put("tx", org.json.JSONArray()))
        val now = System.currentTimeMillis()
        // filler so "data start" is a few months back
        db.insert(Tx(ts = now - 120 * Db.DAY, amount = 1.0, merchant = "x", src = Src.MANUAL))
        val nowCal = Calendar.getInstance()
        val d = nowCal.get(Calendar.DAY_OF_MONTH); val mo = nowCal.get(Calendar.MONTH) + 1
        // 1) Cal SMS: purchase in 3 payments of 900 → plan, purchase not counted, payments counted
        val sms = "היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקת תשלומים ב-$d/$mo בKSP נצרת ב-3 תשלומים בסך 900 שח."
        assertNotNull(Ingest.handle(ctx, sms, now - 3600000, Src.SMS, "CAL"))
        val plan = Plans.all(db).single()
        assertEquals(3, plan.count); assertEquals(900.0, plan.total, 0.001)
        assertEquals(1, db.query("type=?", arrayOf(Types.PLAN)).size)
        // same purchase via Cal push → merged into the purchase, no new expense
        assertNull(Ingest.handle(ctx, sms, now - 3000000, Src.NOTIF_CAL, "com.onoapps.cal4u", "כאל"))
        assertEquals(0, db.query("type=? AND amount=900", arrayOf(Types.EXPENSE)).size)
        // a normal card purchase of 300 (= one payment) must NOT be swallowed by the plan
        Ingest.handle(ctx, "היי, בכרטיס ויזה H&O המסתיים ב-1234 קיבלנו בקשה לעסקה ב-$d/$mo בסופר בסך 300 שח.", now - 1000, Src.SMS, "CAL")
        assertEquals(1, db.query("merchant='סופר'").size)
        // 2) Cal statement line "תשלום 1 מתוך 3" → lands on payment 1, not a new expense
        val row = ImportRow(ts = plan.purchaseTs, amount = 300.0, type = Types.EXPENSE, merchant = "KSP נצרת", note = "", card = "1234",
            installments = 3, instN = 1, total = 900.0)
        Importers.commit(ctx, listOf(row))
        val p1 = db.query("grp=?", arrayOf("plan:${plan.id}:1")).single()
        assertTrue(p1.src and Src.STATEMENT != 0)
        assertEquals(1, Plans.all(db).size)
        // 3) old KSP with no plan, from statement "תשלום 5 מתוך 9" → creates the plan
        Importers.commit(ctx, listOf(ImportRow(ts = ts(2026, 6, 2), amount = 273.0, type = Types.EXPENSE, merchant = "KSP שופינג",
            note = "", installments = 9, instN = 5, total = 2463.0)))
        val ksp = Plans.all(db).first { it.merchant == "KSP שופינג" }
        assertEquals(9, ksp.count)
        assertEquals(1, db.query("grp=?", arrayOf("plan:${ksp.id}:5")).size)
        // 4) manual insurance from the bank, payment generated this month; bank SMS debit merges into it
        val first = Calendar.getInstance().apply { add(Calendar.MONTH, -2); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 9) }.timeInMillis
        val ins = Plan(name = "Страховка машины", monthly = 312.5, count = 12, firstTs = first, source = Plans.BANK, category = "Транспорт и авто")
        Plans.save(ctx, ins)
        val thisMonth = db.query("grp=?", arrayOf("plan:${ins.id}:3")).single()
        assertEquals(312.5, thisMonth.amount, 0.001)
        assertNull(Ingest.handle(ctx, "♦️ 12-345:\nחשבונך חויב בסך 312.50 ש\"ח", thisMonth.ts + 3600000, Src.SMS, "Poalim"))
        assertEquals(1, db.query("amount=312.5 AND ts>?", arrayOf((thisMonth.ts - 3 * Db.DAY).toString())).size)
        // 5) monthly expense total counts the payment, not the full price
        val month = monthStart(now)
        val exp = db.between(month, addMonths(month, 1)).filter { it.type == Types.EXPENSE }.sumOf { it.amount }
        assertTrue(exp < 900 + 300 + 312.5 + 300)
        // 6) delete plan → purchase becomes a full expense again, generated payments gone
        Plans.delete(ctx, Plans.byId(db, plan.id)!!)
        assertEquals(1, db.query("type=? AND amount=900", arrayOf(Types.EXPENSE)).size)
        // chat reply / promo push mentioning a price is not a payment
        assertNull(Ingest.handle(ctx, "Итого вышло 819,70 ₪ за месяц", now, Src.NOTIF_OTHER, "com.some.app", "Получен ответ от Claude"))
        assertNull(Ingest.handle(ctx, "מבצעי נובמבר ב-UGREEN עד 126 ₪", now, Src.NOTIF_OTHER, "com.ugreen", "מבצעי נובמבר"))
        assertEquals(0, db.query("amount=819.7 OR amount=126").size)
        // real bank-app push still recorded, generic title is not a merchant
        assertNotNull(Ingest.handle(ctx, "שילמת 82.72 ₪ בכרטיס ••1234", now, Src.NOTIF_OTHER, "com.poalim", "בוצעה עסקה"))
        assertEquals("", db.query("amount=82.72").single().merchant)
        // alimony: credit to the joint account = expense, the matching debit from the main account = transfer
        Prefs.get(ctx).edit().putString("jointAccount", "12-999").apply()
        Ingest.handle(ctx, "♦️ 12-999:\nחשבונך זוכה בסך\n1,500.00 ש\"ח.", now - 7200000, Src.SMS, "Poalim")
        Importers.commit(ctx, listOf(ImportRow(ts = now - 3600000, amount = 1500.0, type = Types.EXPENSE, merchant = "העברה-נייד", note = "")))
        val al = db.query("amount=1500")
        assertEquals(1, al.count { it.type == Types.EXPENSE && it.category == Transfers.ALIMONY })
        assertEquals(1, al.count { it.type == Types.TRANSFER })
        // backup roundtrip keeps plans
        val n = Plans.all(db).size
        db.importJson(db.exportJson())
        assertEquals(n, Plans.all(db).size)
    }
}

package com.shakhov.finance

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor

/** Installment plan (рассрочка): purchase by card in N payments, or a manual plan (e.g. car insurance from the bank account). */
data class Plan(
    var id: Long = 0,
    var name: String,
    var merchant: String = "",
    var total: Double = 0.0,
    var count: Int = 0,
    var monthly: Double = 0.0,
    var firstTs: Long = 0,
    var purchaseTs: Long = 0,
    var source: String = Plans.CARD,
    var card: String = "",
    var account: String = "",
    var category: String = "Другое",
    var note: String = "",
    var purchaseTxId: Long = 0
)

object Plans {
    const val CARD = "card"
    const val BANK = "bank"

    fun chargeDay(ctx: Context) = Prefs.get(ctx).getInt("calDay", 10)

    /** First card charge day strictly after the purchase date. */
    fun nextCharge(purchaseTs: Long, day: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = purchaseTs }
        val r = Calendar.getInstance().apply {
            timeInMillis = purchaseTs
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        if (!r.after(c)) {
            r.set(Calendar.DAY_OF_MONTH, 1); r.add(Calendar.MONTH, 1)
            r.set(Calendar.DAY_OF_MONTH, minOf(day, r.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return r.timeInMillis
    }

    fun paymentTs(p: Plan, n: Int): Long = Calendar.getInstance().apply { timeInMillis = p.firstTs; add(Calendar.MONTH, n - 1) }.timeInMillis

    /** Payment n (1-based). Like Israeli card companies: whole shekels, the first payment takes the remainder. */
    fun payment(p: Plan, n: Int): Double {
        if (p.count <= 0) return 0.0
        if (p.monthly > 0) {
            if (p.total > 0 && n == p.count) return round2(maxOf(0.0, p.total - p.monthly * (p.count - 1)))
            return p.monthly
        }
        val base = floor(p.total / p.count)
        return if (n == 1) round2(p.total - base * (p.count - 1)) else base
    }

    fun total(p: Plan) = if (p.total > 0) p.total else p.monthly * p.count
    fun paidCount(p: Plan, now: Long = System.currentTimeMillis()) = (1..p.count).count { paymentTs(p, it) <= now }
    fun paidSum(p: Plan, now: Long = System.currentTimeMillis()) = (1..paidCount(p, now)).sumOf { payment(p, it) }
    fun remaining(p: Plan, now: Long = System.currentTimeMillis()) = round2(total(p) - paidSum(p, now))
    fun inMonth(p: Plan, month: Long): Double {
        val end = addMonths(month, 1)
        return (1..p.count).filter { paymentTs(p, it) in month until end }.sumOf { payment(p, it) }
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    // ---------- storage ----------
    private fun cv(p: Plan) = ContentValues().apply {
        put("name", p.name); put("merchant", p.merchant); put("total", p.total); put("count", p.count); put("monthly", p.monthly)
        put("firstTs", p.firstTs); put("purchaseTs", p.purchaseTs); put("source", p.source); put("card", p.card)
        put("account", p.account); put("category", p.category); put("note", p.note); put("purchaseTxId", p.purchaseTxId)
    }

    fun all(db: Db): List<Plan> = db.readableDatabase.rawQuery(
        "SELECT id,name,merchant,total,count,monthly,firstTs,purchaseTs,source,card,account,category,note,purchaseTxId FROM plans ORDER BY firstTs", null
    ).use { c ->
        val l = mutableListOf<Plan>()
        while (c.moveToNext()) l += Plan(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getDouble(3), c.getInt(4), c.getDouble(5),
            c.getLong(6), c.getLong(7), c.getString(8) ?: CARD, c.getString(9) ?: "", c.getString(10) ?: "", c.getString(11) ?: "Другое",
            c.getString(12) ?: "", c.getLong(13))
        l
    }

    fun byId(db: Db, id: Long) = all(db).firstOrNull { it.id == id }

    fun insertRaw(db: Db, p: Plan): Long { val v = cv(p); if (p.id > 0) v.put("id", p.id); p.id = db.writableDatabase.insert("plans", null, v); return p.id }

    fun toJson(p: Plan) = JSONObject().put("id", p.id).put("name", p.name).put("merchant", p.merchant).put("total", p.total)
        .put("count", p.count).put("monthly", p.monthly).put("firstTs", p.firstTs).put("purchaseTs", p.purchaseTs)
        .put("source", p.source).put("card", p.card).put("account", p.account).put("category", p.category)
        .put("note", p.note).put("purchaseTxId", p.purchaseTxId)

    fun fromJson(o: JSONObject) = Plan(o.optLong("id"), o.optString("name"), o.optString("merchant"), o.optDouble("total", 0.0),
        o.optInt("count"), o.optDouble("monthly", 0.0), o.optLong("firstTs"), o.optLong("purchaseTs"), o.optString("source", CARD),
        o.optString("card"), o.optString("account"), o.optString("category", "Другое"), o.optString("note"), o.optLong("purchaseTxId"))

    private fun tag(p: Plan, n: Int) = "plan:${p.id}:$n"

    // ---------- create / edit / delete ----------
    /** Card purchase in installments (from SMS/notification): full price becomes "plan" type, payments become expenses. */
    fun fromPurchase(ctx: Context, t: Tx, count: Int): Plan {
        val db = Db.get(ctx)
        val p = Plan(name = t.merchantRu.ifEmpty { t.merchant }, merchant = t.merchant, total = t.amount, count = maxOf(0, count),
            purchaseTs = t.ts, firstTs = nextCharge(t.ts, chargeDay(ctx)), source = CARD, card = t.card,
            category = t.category.takeIf { it in Cats.expense } ?: "Другое", purchaseTxId = t.id)
        insertRaw(db, p)
        t.type = Types.PLAN; t.grp = tag(p, 0); db.update(t)
        sync(ctx, p)
        return p
    }

    fun save(ctx: Context, p: Plan) {
        val db = Db.get(ctx)
        if (p.source == CARD && p.purchaseTs > 0) p.firstTs = nextCharge(p.purchaseTs, chargeDay(ctx))
        if (p.id == 0L) insertRaw(db, p) else db.writableDatabase.update("plans", cv(p), "id=?", arrayOf(p.id.toString()))
        // purchase line follows the plan's name/category
        if (p.purchaseTxId > 0) db.byId(p.purchaseTxId)?.let { it.category = p.category; if (p.name.isNotEmpty()) it.merchantRu = p.name; db.update(it) }
        regenerate(ctx, p)
    }

    fun delete(ctx: Context, p: Plan) {
        val db = Db.get(ctx)
        db.query("grp LIKE ?", arrayOf("plan:${p.id}:%")).forEach { t ->
            when {
                t.grp == tag(p, 0) -> { t.type = Types.EXPENSE; t.grp = ""; db.update(t) }
                t.src == Src.PLAN -> db.delete(t.id)
                else -> { t.grp = ""; t.src = t.src and Src.PLAN.inv(); db.update(t) }
            }
        }
        db.writableDatabase.delete("plans", "id=?", arrayOf(p.id.toString()))
        db.changed(); Backup.schedule(ctx)
    }

    /** Removes generated (not yet confirmed) payments and creates them again from the plan. */
    fun regenerate(ctx: Context, p: Plan) {
        val db = Db.get(ctx)
        db.query("grp LIKE ?", arrayOf("plan:${p.id}:%")).forEach { t ->
            val n = t.grp.substringAfterLast(':').toIntOrNull() ?: 0
            if (n == 0) return@forEach
            if (t.src == Src.PLAN) db.delete(t.id)
            else {
                if (n > p.count) { t.grp = ""; t.src = t.src and Src.PLAN.inv() } else t.ts = paymentTs(p, n)
                t.category = p.category; t.merchantRu = p.name; t.note = "платёж $n из ${p.count}"
                db.update(t)
            }
        }
        sync(ctx, p)
        db.changed(); Backup.schedule(ctx)
    }

    fun onChargeDayChanged(ctx: Context) {
        val db = Db.get(ctx)
        all(db).filter { it.source == CARD && it.purchaseTs > 0 }.forEach { save(ctx, it) }
    }

    /** Earliest month that has any real data — no payments are generated before it. */
    private fun dataStart(db: Db): Long =
        db.readableDatabase.rawQuery("SELECT MIN(ts) FROM tx WHERE (src & ${Src.PLAN})=0", null).use {
            if (it.moveToFirst() && !it.isNull(0)) monthStart(it.getLong(0)) else monthStart(System.currentTimeMillis())
        }

    /** Creates expense records for payments due up to the end of the current month. */
    fun syncAll(ctx: Context) { all(Db.get(ctx)).forEach { sync(ctx, it) }; Db.get(ctx).changed() }

    fun sync(ctx: Context, p: Plan) {
        if (p.count <= 0 || p.firstTs == 0L) return
        val db = Db.get(ctx)
        val start = minOf(dataStart(db), monthStart(p.purchaseTs.takeIf { it > 0 } ?: p.firstTs))
        val end = addMonths(monthStart(System.currentTimeMillis()), 1)
        val existing = db.query("grp LIKE ?", arrayOf("plan:${p.id}:%")).map { it.grp }.toSet()
        for (n in 1..p.count) {
            val ts = paymentTs(p, n)
            if (ts < start) continue
            if (ts >= end) break
            if (tag(p, n) in existing) continue
            val amt = payment(p, n)
            // a real debit (bank SMS, statement) may already be there — attach it instead of adding a second one
            val real = db.query("type=? AND ts BETWEEN ? AND ? AND grp=''", arrayOf(Types.EXPENSE, (ts - 5 * Db.DAY).toString(), (ts + 5 * Db.DAY).toString()))
                .filter { abs(it.amount - amt) < 0.005 }
                .filter {
                    // card installments arrive only in the Cal statement; bank plans — as an account debit (SMS / bank statement)
                    if (p.source == CARD) it.src and Src.STATEMENT != 0 && Parsers.merchantKey(it.merchant) == Parsers.merchantKey(p.merchant)
                    else it.card.isEmpty() && it.src and (Src.SMS or Src.STATEMENT or Src.NOTIF_OTHER) != 0
                }
                .minByOrNull { abs(it.ts - ts) }
            if (real != null) {
                real.grp = tag(p, n); real.src = real.src or Src.PLAN; real.category = p.category
                real.merchantRu = p.name; real.note = "платёж $n из ${p.count}"; db.update(real)
            } else db.insert(Tx(
                ts = ts, amount = amt, type = Types.EXPENSE, category = p.category, merchant = p.merchant, merchantRu = p.name,
                note = "платёж $n из ${p.count}", card = p.card, account = p.account, src = Src.PLAN, grp = tag(p, n)
            ))
        }
    }

    /** Statement line "תשלום N מתוך M": lands on payment N of the matching plan (or creates the plan). */
    fun applyStatementRow(ctx: Context, r: ImportRow): Boolean {
        val db = Db.get(ctx)
        val key = Parsers.merchantKey(r.merchant)
        var p = all(db).filter { Parsers.merchantKey(it.merchant) == key && (it.count == r.installments || it.count == 0) }
            .minByOrNull { abs((it.total) - (r.total.takeIf { t -> t > 0 } ?: it.total)) }
        var isNew = false
        if (p == null) {
            val total = if (r.total > 0) r.total else r.amount * r.installments
            // purchase date from the statement row (Cal lists installment lines with the original purchase date)
            val t = Tx(ts = r.ts, amount = total, type = Types.EXPENSE, merchant = r.merchant, card = r.card, src = Src.STATEMENT,
                note = "покупка в рассрочку, ${r.installments} платежей")
            Dict.enrich(ctx, t, null)
            db.insert(t)
            p = fromPurchase(ctx, t, r.installments); isNew = true
        } else if (p.count == 0) { p.count = r.installments; save(ctx, p) }
        val n = r.instN.coerceIn(1, p.count)
        val tag = "plan:${p.id}:$n"
        val pay = db.query("grp=?", arrayOf(tag)).firstOrNull()
        if (pay != null) { pay.amount = r.amount; pay.src = pay.src or Src.STATEMENT; db.update(pay) }
        else db.insert(Tx(ts = paymentTs(p, n), amount = r.amount, type = Types.EXPENSE, category = p.category, merchant = p.merchant,
            merchantRu = p.name, note = "платёж $n из ${p.count}", card = p.card, src = Src.STATEMENT or Src.PLAN, grp = tag))
        db.changed()
        return isNew || pay == null
    }

    /** One-time: turn installment purchases already in the database into plans. */
    fun migrate(ctx: Context) {
        val db = Db.get(ctx)
        // from SMS: full-price purchase marked with installments
        db.query("type=? AND installments!=0 AND grp=''", arrayOf(Types.EXPENSE)).forEach { t ->
            fromPurchase(ctx, t, maxOf(0, t.installments))
        }
        // from the old table: "рассрочка: платёж 5 из 9, покупка 02.06.2026 на 2463 ₪"
        val re = Regex("платёж (\\d+) из (\\d+), покупка (\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}) на ([\\d.,]+)")
        db.query("src=? AND grp=''", arrayOf(Src.SEED.toString())).forEach { t ->
            val m = re.find(t.note) ?: return@forEach
            val (n, cnt, d, mo, y, tot) = m.destructured
            val purchase = Calendar.getInstance().apply { set(y.toInt(), mo.toInt() - 1, d.toInt(), 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val p = Plan(name = t.merchantRu.ifEmpty { t.merchant }, merchant = t.merchant, total = tot.replace(",", ".").toDouble(),
                count = cnt.toInt(), purchaseTs = purchase, firstTs = nextCharge(purchase, chargeDay(ctx)), card = t.card, category = t.category)
            insertRaw(db, p)
            t.grp = "plan:${p.id}:$n"; t.src = t.src or Src.PLAN; t.note = "платёж $n из $cnt"; db.update(t)
            sync(ctx, p)
        }
        db.changed()
    }
}

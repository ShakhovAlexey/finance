package com.shakhov.finance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

/**
 * Planning: savings goals (копилки — e.g. 10% of income to «Отпуск»), category limits,
 * recurring payments and the end-of-month forecast.
 */
object Budget {
    data class Goal(val name: String, val icon: String = "🎯", val target: Double = 0.0, val pct: Double = 0.0, val fixed: Double = 0.0)

    // ---------- goals ----------
    fun goals(ctx: Context): List<Goal> {
        val a = JSONArray(Prefs.get(ctx).getString("goals", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Goal(o.getString("name"), o.optString("icon", "🎯"), o.optDouble("target", 0.0), o.optDouble("pct", 0.0), o.optDouble("fixed", 0.0))
        } }
    }

    private fun saveGoals(ctx: Context, l: List<Goal>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("name", it.name).put("icon", it.icon).put("target", it.target).put("pct", it.pct).put("fixed", it.fixed)) }
        Prefs.get(ctx).edit().putString("goals", a.toString()).apply()
    }

    /** Adds or updates a goal; the goal is also an expense category (spending from it, e.g. paying for the trip). */
    fun putGoal(ctx: Context, g: Goal, oldName: String? = null) {
        val l = goals(ctx).filter { it.name != (oldName ?: g.name) } + g
        saveGoals(ctx, l)
        if (oldName != null && oldName != g.name) {
            Db.get(ctx).writableDatabase.execSQL("UPDATE tx SET category=? WHERE category=?", arrayOf(g.name, oldName))
            if (g.name !in Cats.all()) Cats.rename(ctx, oldName, g.name, g.icon) else Cats.delete(ctx, oldName)
        } else if (g.name !in Cats.expense) Cats.add(ctx, g.name, g.icon)
        Db.get(ctx).changed(); Backup.schedule(ctx)
    }

    fun deleteGoal(ctx: Context, name: String) {
        saveGoals(ctx, goals(ctx).filter { it.name != name })
        // savings records go away, spending stays as a normal category
        Db.get(ctx).writableDatabase.execSQL("DELETE FROM tx WHERE type=? AND category=?", arrayOf(Types.SAVE, name))
        Db.get(ctx).changed(); Backup.schedule(ctx)
    }

    fun planned(g: Goal, income: Double) = if (g.pct > 0) Math.round(income * g.pct / 100).toDouble() else g.fixed

    fun savedIn(ctx: Context, g: String, from: Long, to: Long) =
        Db.get(ctx).query("type=? AND category=? AND ts>=? AND ts<?", arrayOf(Types.SAVE, g, from.toString(), to.toString())).sumOf { it.amount }

    fun balance(ctx: Context, g: String): Double {
        val db = Db.get(ctx)
        val saved = db.query("type=? AND category=?", arrayOf(Types.SAVE, g)).sumOf { it.amount }
        val spent = db.query("type=? AND category=?", arrayOf(Types.EXPENSE, g)).sumOf { it.amount }
        return saved - spent
    }

    fun deposit(ctx: Context, g: Goal, amount: Double, ts: Long = System.currentTimeMillis()) {
        if (amount == 0.0) return
        Db.get(ctx).insert(Tx(ts = ts, amount = amount, type = Types.SAVE, category = g.name, merchantRu = "В копилку «${g.name}»", src = Src.MANUAL))
        Db.get(ctx).changed(); Backup.schedule(ctx)
    }

    /** Planned savings of the month not yet put aside (so «можно потратить» already excludes them). */
    fun reserved(ctx: Context, month: Long, income: Double): Double {
        val end = addMonths(month, 1)
        return goals(ctx).sumOf { g -> maxOf(planned(g, income), savedIn(ctx, g.name, month, end)) }
    }

    // ---------- limits ----------
    fun limits(ctx: Context): Map<String, Double> {
        val o = JSONObject(Prefs.get(ctx).getString("limits", "{}"))
        return o.keys().asSequence().associateWith { o.getDouble(it) }
    }

    fun setLimit(ctx: Context, cat: String, v: Double?) {
        val o = JSONObject(Prefs.get(ctx).getString("limits", "{}"))
        if (v == null || v <= 0) o.remove(cat) else o.put(cat, v)
        Prefs.get(ctx).edit().putString("limits", o.toString()).apply()
        Db.get(ctx).changed(); Backup.schedule(ctx)
    }

    fun renameCategory(ctx: Context, old: String, new: String?) {
        val o = JSONObject(Prefs.get(ctx).getString("limits", "{}"))
        if (o.has(old)) { val v = o.getDouble(old); o.remove(old); if (new != null) o.put(new, v) }
        Prefs.get(ctx).edit().putString("limits", o.toString()).apply()
    }

    fun spent(ctx: Context, cat: String, month: Long) =
        Db.get(ctx).query("type=? AND category=? AND currency='ILS' AND ts>=? AND ts<?", arrayOf(Types.EXPENSE, cat, month.toString(), addMonths(month, 1).toString())).sumOf { it.amount }

    /** Message when an expense just crossed 80% or 100% of the category limit. */
    fun limitAlert(ctx: Context, t: Tx): String? {
        if (t.type != Types.EXPENSE) return null
        val lim = limits(ctx)[t.category] ?: return null
        val month = monthStart(t.ts)
        val now = spent(ctx, t.category, month); val before = now - t.amount
        return when {
            before < lim && now >= lim -> "${t.category}: лимит ${fmtMoney(lim)} превышен (${fmtMoney(now)})"
            before < lim * 0.8 && now >= lim * 0.8 -> "${t.category}: потрачено ${Math.round(now * 100 / lim)}% лимита"
            else -> null
        }
    }

    // ---------- recurring payments ----------
    data class Recurring(val key: String, val name: String, val category: String, val amount: Double, val day: Int, val paidTs: Long?)

    private fun keyOf(t: Tx): String? = when {
        t.grp.startsWith("plan:") -> null  // installments are shown separately
        t.category == Transfers.ALIMONY -> "cat:${Transfers.ALIMONY}"
        t.merchant.isNotBlank() -> Parsers.merchantKey(t.merchant).replace(Regex("\\*.*$"), "").trim().takeIf { it.length >= 2 }
        else -> null
    }

    /** Same shop charged in at least 2 of the 3 previous months with a similar amount (±15%). */
    fun recurring(ctx: Context, month: Long): List<Recurring> {
        val db = Db.get(ctx)
        val all = db.query("type=? AND ts>=? AND ts<?", arrayOf(Types.EXPENSE, addMonths(month, -3).toString(), addMonths(month, 1).toString()))
        val keyed = all.mapNotNull { t -> keyOf(t)?.let { it to t } }          // key computed once per operation
        val byKey = keyed.groupBy({ it.first }, { it.second })
        val prevStarts = (1..3).map { addMonths(month, -it) }
        val out = mutableListOf<Recurring>()
        for ((k, txs) in byKey) {
            val hits = prevStarts.map { m -> val e = addMonths(m, 1); txs.filter { it.ts >= m && it.ts < e } }
            if (hits.count { it.isNotEmpty() } < 2) continue
            if (hits.any { it.size > 1 }) continue
            val last = hits.first { it.isNotEmpty() }.first()
            if (hits.filter { it.isNotEmpty() }.any { abs(it.first().amount - last.amount) > last.amount * 0.15 }) continue
            val paid = txs.firstOrNull { it.ts >= month }
            val day = Calendar.getInstance().apply { timeInMillis = last.ts }.get(Calendar.DAY_OF_MONTH)
            out += Recurring(k, last.merchantRu.ifEmpty { last.merchant }.ifEmpty { last.category }, last.category, paid?.amount ?: last.amount, day, paid?.ts)
        }
        return out.sortedBy { it.day }
    }

    // ---------- forecast ----------
    data class Forecast(val income: Double, val expense: Double, val reserved: Double, val rest: Double, val expectedRecurring: Double, val expectedSalary: Double)

    fun forecast(ctx: Context, month: Long, now: Long = System.currentTimeMillis(), recurringList: List<Recurring>? = null): Forecast {
        val db = Db.get(ctx)
        val list = db.between(month, addMonths(month, 1))
        val exp = list.filter { it.type == Types.EXPENSE && it.currency == "ILS" }
        val spent = exp.sumOf { it.amount }
        var income = list.filter { it.type == Types.INCOME && it.currency == "ILS" }.sumOf { it.amount }
        val rec = recurringList ?: recurring(ctx, month)
        val recKeys = rec.map { it.key }.toSet()
        val expectedRec = rec.filter { it.paidTs == null }.sumOf { it.amount }
        // salary not yet in: expect last month's
        var expSalary = 0.0
        if (list.none { it.category == "Зарплата" }) {
            expSalary = db.between(addMonths(month, -1), month).filter { it.category == "Зарплата" }.sumOf { it.amount }
            income += expSalary
        }
        val end = addMonths(month, 1)
        val elapsed = ((minOf(now, end) - month) / Db.DAY).toInt().coerceAtLeast(1)
        val left = ((end - maxOf(now, month)) / Db.DAY).toInt().coerceAtLeast(0)
        // day-to-day spending pace: without recurring, installments and alimony
        val variable = exp.filter { !it.grp.startsWith("plan:") && it.category != Transfers.ALIMONY && keyOf(it)?.let { k -> k in recKeys } != true }.sumOf { it.amount }
        val future = (1..left).let { if (left == 0) 0.0 else variable / elapsed * left }
        val totalExp = spent + expectedRec + future
        val reserved = reserved(ctx, month, income)
        return Forecast(income, totalExp, reserved, income - totalExp - reserved, expectedRec, expSalary)
    }
}

/** Everything the «План» tab shows, computed off the main thread. */
data class BudgetData(
    val income: Double,
    val forecast: Budget.Forecast,
    val recurring: List<Budget.Recurring>,
    val goals: List<Triple<Budget.Goal, Double, Double>>,     // goal, saved this month, balance
    val limits: List<Triple<String, Double, Double>>          // category, limit, spent
) {
    companion object {
        fun load(ctx: Context): BudgetData {
            val now = System.currentTimeMillis(); val month = monthStart(now); val end = addMonths(month, 1)
            val list = Db.get(ctx).between(month, end)
            val income = list.filter { it.type == Types.INCOME && it.currency == "ILS" }.sumOf { it.amount }
            val rec = Budget.recurring(ctx, month)
            val fc = Budget.forecast(ctx, month, now, rec)
            val goals = Budget.goals(ctx).map { g -> Triple(g, Budget.savedIn(ctx, g.name, month, end), Budget.balance(ctx, g.name)) }
            val limits = Budget.limits(ctx).map { (c, l) -> Triple(c, l, list.filter { it.type == Types.EXPENSE && it.category == c && it.currency == "ILS" }.sumOf { it.amount }) }
            return BudgetData(income, fc, rec, goals, limits)
        }
    }
}

package com.shakhov.finance

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

fun fmtMoney(v: Double, cur: String = "ILS"): String {
    val s = String.format(Locale("ru"), "%,.2f", v).replace(",00", "")
    return when (cur) { "ILS" -> "$s ₪"; "USD" -> "$$s"; "EUR" -> "€$s"; else -> "$s $cur" }
}

val ruLocale = Locale("ru")
fun fmtDate(ts: Long, p: String = "d MMMM") = SimpleDateFormat(p, ruLocale).format(Date(ts))

fun monthStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun addMonths(ts: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = ts; add(Calendar.MONTH, n) }.timeInMillis

fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

object Prefs {
    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Shape of a message without numbers — to hide similar unrecognised messages. */
    fun signature(text: String) = Parsers.clean(text).replace(Regex("[\\d.,:/₪-]+"), "#").replace(Regex("\\s+"), " ").take(40)
    fun ignored(ctx: Context): Set<String> = get(ctx).getStringSet("ignoredSigs", emptySet())!!
    fun ignore(ctx: Context, text: String) { get(ctx).edit().putStringSet("ignoredSigs", ignored(ctx) + signature(text)).apply() }

    /** Apps (not yet enabled) that posted notifications with shekel amounts. */
    fun rememberPkg(ctx: Context, pkg: String, sample: String) {
        val p = get(ctx)
        val o = JSONObject(p.getString("seenPkgs", "{}")!!)
        o.put(pkg, sample.take(200))
        p.edit().putString("seenPkgs", o.toString()).apply()
    }

    fun seenPkgs(ctx: Context): Map<String, String> {
        val o = JSONObject(get(ctx).getString("seenPkgs", "{}")!!)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }
}

/** One-time fixes after updates. */
object Migrate {
    fun run(ctx: Context) {
        val p = Prefs.get(ctx)
        if (!p.getBoolean("fix_ads_v3", false)) {
            val db = Db.get(ctx)
            db.query("src=?", arrayOf(Src.SMS.toString())).filter { it.raw.isNotEmpty() && Parsers.parse(it.raw) == null }
                .forEach { db.delete(it.id) }
            p.edit().putBoolean("fix_ads_v3", true).apply()
            db.changed()
        }
        if (!p.getBoolean("fix_notif_v6", false)) {
            // remove "payments" that were really chat replies or promo pushes
            val db = Db.get(ctx)
            val notifOnly = listOf(Src.NOTIF_OTHER, Src.NOTIF_CAL)
            db.query("src IN (${Src.NOTIF_OTHER},${Src.NOTIF_CAL})").filter { it.src in notifOnly }.forEach { t ->
                val raw = Parsers.clean(t.raw)
                if (raw.contains("Claude") || t.merchant.contains("Claude") || Parsers.isAd(raw) || Parsers.isAd(Parsers.clean(t.merchant))) db.delete(t.id)
            }
            db.query("merchant LIKE 'בוצעה עסקה%'").forEach { it.merchant = ""; it.merchantRu = ""; db.update(it) }
            val enabled = p.getStringSet("pkgs", emptySet())!!.filterNot { NotifService.blocked(it) }.toSet()
            p.edit().putStringSet("pkgs", enabled).putBoolean("fix_notif_v6", true).apply()
            db.changed()
        }
        if (!p.getBoolean("repair_v7", false)) { Repair.run(ctx); p.edit().putBoolean("repair_v7", true).apply() }
        Transfers.link(ctx)
        if (!p.getBoolean("names_v11", false)) {
            // replace machine transliterations («Аиис Нцрт») with known chain names
            val db = Db.get(ctx)
            db.query("merchant!=''").forEach { t ->
                val rule = db.rule(Parsers.merchantKey(t.merchant))?.first?.takeIf { it.isNotEmpty() }
                if (rule != null) return@forEach
                val auto = t.merchantRu.isEmpty() || t.merchantRu == Dict.translate(ctx, t.merchant) || t.merchantRu == Parsers.translit(t.merchant)
                if (!auto) return@forEach
                Dict.merchantName(ctx, t.merchant)?.let { t.merchantRu = it; db.update(t) }
            }
            p.edit().putBoolean("names_v11", true).apply(); db.changed()
        }
        if (!p.getBoolean("max_v8", false)) {
            val db = Db.get(ctx)
            db.query("type IN (?,?)", arrayOf(Types.TRANSFER, Types.EXPENSE)).filter { Max.isMax(it.merchant) || (it.card.isEmpty() && Max.isMax(it.raw)) }
                .forEach { Max.apply(it); db.update(it) }
            p.edit().putBoolean("max_v8", true).apply(); db.changed()
        }
        if (!p.getBoolean("plans_v5", false)) {
            Plans.migrate(ctx)
            p.edit().putBoolean("plans_v5", true).apply()
        }
        Plans.syncAll(ctx)
        if (!p.getBoolean("fix_seed_grp_v4", false)) {
            // Old table split the Merkaza receipt (538,68 ₪) into 4 categories without a group mark —
            // mark it so a later SMS/statement of 538,68 merges instead of adding a duplicate.
            val db = Db.get(ctx)
            db.query("src=? AND note LIKE '%538,68%'", arrayOf(Src.SEED.toString())).forEach {
                it.grp = "seed-merkaza-0811"; it.grpTotal = 538.68; db.update(it)
            }
            p.edit().putBoolean("fix_seed_grp_v4", true).apply()
            db.changed()
        }
    }
}

/** One-time import of the old «Мои траты» table. */
object Seed {
    fun run(ctx: Context) {
        val p = Prefs.get(ctx)
        if (p.getBoolean("seeded", false)) return
        val db = Db.get(ctx)
        // seed.json (old «Мои траты» table) is personal data: kept out of git, only in the first locally built APK
        val text = runCatching { ctx.assets.open("seed.json").bufferedReader().readText() }.getOrNull()
        if (text == null) { p.edit().putBoolean("seeded", true).apply(); return }
        val a = JSONArray(text)
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val items = o.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).map { j ->
                    val it = arr.getJSONArray(j)
                    Item(it.getString(0), it.getString(1), it.getDouble(2), it.getString(3), o.getString("c"))
                }
            } ?: emptyList()
            val t = Tx(
                ts = df.parse(o.getString("d") + " 12:00")!!.time, amount = o.getDouble("a"), category = o.getString("c"),
                merchant = o.getString("m"), merchantRu = o.optString("mr"), note = o.optString("n"),
                card = o.optString("card"), src = Src.SEED, items = items
            )
            db.insert(t)
            val key = Parsers.merchantKey(t.merchant)
            if (key.isNotEmpty() && t.category !in listOf("Алкоголь", "Хозтовары", "Гигиена"))
                db.setRule(key, t.merchantRu.ifEmpty { null }, t.category)
        }
        p.edit().putBoolean("seeded", true).putLong("smsFrom", df.parse("2026-11-15 00:00")!!.time).apply()
        db.changed()
    }
}

/** Automatic copy of all data to a file the user picked once (e.g. in Google Drive). */
object Backup {
    fun uri(ctx: Context): Uri? = Prefs.get(ctx).getString("backupUri", null)?.let { Uri.parse(it) }

    private val lock = Any()

    /** Copy right away in a background thread (app closed, SMS recorded). */
    fun writeNowAsync(ctx: Context) { val c = ctx.applicationContext; Thread { write(c) }.start() }

    fun write(ctx: Context): Boolean = synchronized(lock) { writeLocked(ctx) }

    private fun writeLocked(ctx: Context): Boolean {
        val u = uri(ctx) ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(u, "wt")!!.use { it.write(Db.get(ctx).exportJson().toString().toByteArray()) }
            Prefs.get(ctx).edit().putLong("backupAt", System.currentTimeMillis()).apply()
            true
        }.getOrDefault(false)
    }

    fun writeTo(ctx: Context, u: Uri): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(u, "wt")!!.use { it.write(Db.get(ctx).exportJson().toString().toByteArray()) }; true
    }.getOrDefault(false)

    fun restore(ctx: Context, u: Uri) {
        val s = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Db.get(ctx).importJson(JSONObject(s))
        Prefs.get(ctx).edit().putBoolean("seeded", true).apply()
    }

    /** Debounced: copy 2 minutes after the last change; plus a daily safety copy. */
    fun schedule(ctx: Context) {
        if (uri(ctx) == null) return
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniqueWork("backup-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(2, TimeUnit.MINUTES).build())
        wm.enqueueUniquePeriodicWork("backup-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build())
    }
}

class BackupWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result = if (Backup.write(applicationContext)) Result.success() else Result.retry()
}

/** Fixes data recorded by earlier versions (Cal pushes without shop, double pushes, statement vs split receipt, installment amounts). */
object Repair {
    fun run(ctx: Context) {
        val db = Db.get(ctx)
        // 1) Cal pushes recorded without shop/card: read them again
        db.query("merchant='' AND raw LIKE '%בוצעה עסקה%'").forEach { t ->
            val text = t.raw.substringBefore("\n———").replace(Regex("^\\[[^]]*]\\s*"), "")
            (Parsers.parse(text) as? Parsers.Result.T)?.p?.let { pp ->
                t.merchant = pp.merchant; if (t.card.isEmpty()) t.card = pp.card
                if (t.category == "Другое") { val c = t.copy(merchantRu = ""); Dict.enrich(ctx, c, pp.kind); t.category = c.category; if (t.merchantRu.isEmpty()) t.merchantRu = c.merchantRu }
                db.update(t)
            }
        }
        // 2) same push twice within 5 minutes
        val notif = Src.NOTIF_CAL or Src.NOTIF_WALLET or Src.NOTIF_OTHER
        val all = db.query("(src & ?)!=0", arrayOf(notif.toString()), "ts ASC")
        val gone = mutableSetOf<Long>()
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]; val b = all[j]
            if (b.ts - a.ts > 300000) break
            if (a.id in gone || b.id in gone) continue
            if (abs(a.amount - b.amount) < 0.005 && Parsers.sameMerchant(a.merchant, b.merchant) && b.src and notif == b.src) { db.delete(b.id); gone += b.id }
        }
        // 3) a statement line equal to a split receipt (e.g. Merkaza 538,68) — keep the split, drop the line
        db.query("grp!='' AND grp NOT LIKE 'plan:%' AND grpTotal>0").groupBy { it.grp }.forEach { (_, g) ->
            val ts = g.first().ts; val total = g.first().grpTotal
            db.query("src=? AND grp='' AND ts BETWEEN ? AND ?", arrayOf(Src.STATEMENT.toString(), (ts - Db.DAY - Db.DAY / 2).toString(), (ts + Db.DAY + Db.DAY / 2).toString()))
                .firstOrNull { abs(it.amount - total) < 0.005 }?.let { line ->
                    db.delete(line.id); g.forEach { it.src = it.src or Src.STATEMENT; db.update(it) }
                }
        }
        // 4) installment payments that got the full purchase price
        Plans.all(db).forEach { pl ->
            db.query("grp LIKE ?", arrayOf("plan:${pl.id}:%")).forEach { t ->
                val n = t.grp.substringAfterLast(':').toIntOrNull() ?: 0
                if (n > 0 && pl.count > 1 && t.amount > Plans.total(pl) / 2) { t.amount = Plans.payment(pl, n); db.update(t) }
            }
        }
        db.changed()
    }
}

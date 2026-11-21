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

    fun write(ctx: Context): Boolean {
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

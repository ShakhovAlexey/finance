package com.shakhov.finance

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "finance.db", null, 2) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
        const val DAY = 86400000L
        /** Listeners notified after any change (UI refresh + backup scheduling). */
        val listeners = mutableListOf<() -> Unit>()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, amount REAL, currency TEXT,
            type TEXT, category TEXT, merchant TEXT, merchantRu TEXT, note TEXT, card TEXT, account TEXT,
            src INTEGER, items TEXT, raw TEXT, hash TEXT, installments INTEGER, grp TEXT, grpTotal REAL)"""
        )
        db.execSQL("CREATE INDEX tx_ts ON tx(ts)")
        db.execSQL("CREATE INDEX tx_hash ON tx(hash)")
        db.execSQL("CREATE TABLE rules(mkey TEXT PRIMARY KEY, merchantRu TEXT, category TEXT)")
        db.execSQL("CREATE TABLE words(heb TEXT PRIMARY KEY, rus TEXT)")
        db.execSQL("CREATE TABLE balances(account TEXT PRIMARY KEY, amount REAL, ts INTEGER)")
        db.execSQL("CREATE TABLE unparsed(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, source TEXT, sender TEXT, text TEXT, hash TEXT UNIQUE)")
        db.execSQL("CREATE TABLE seen(hash TEXT PRIMARY KEY)")
        createPlans(db)
    }

    private fun createPlans(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS plans(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, merchant TEXT, total REAL,
            count INTEGER, monthly REAL, firstTs INTEGER, purchaseTs INTEGER, source TEXT, card TEXT, account TEXT,
            category TEXT, note TEXT, purchaseTxId INTEGER)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createPlans(db)
    }

    fun changed() { listeners.toList().forEach { runCatching { it() } } }

    // ---------- tx ----------
    private fun itemsToJson(items: List<Item>): String {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("name", it.name).put("ru", it.nameRu).put("price", it.price).put("sub", it.sub).put("cat", it.category))
        }
        return a.toString()
    }

    fun itemsFromJson(s: String?): List<Item> {
        if (s.isNullOrEmpty()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.optString("name"), o.optString("ru"), o.optDouble("price", 0.0), o.optString("sub"), o.optString("cat"))
        }
    }

    private fun cv(t: Tx) = ContentValues().apply {
        put("ts", t.ts); put("amount", t.amount); put("currency", t.currency); put("type", t.type)
        put("category", t.category); put("merchant", t.merchant); put("merchantRu", t.merchantRu); put("note", t.note)
        put("card", t.card); put("account", t.account); put("src", t.src); put("items", itemsToJson(t.items))
        put("raw", t.raw); put("hash", t.hash); put("installments", t.installments); put("grp", t.grp); put("grpTotal", t.grpTotal)
    }

    private fun read(c: Cursor) = Tx(
        id = c.getLong(0), ts = c.getLong(1), amount = c.getDouble(2), currency = c.getString(3) ?: "ILS",
        type = c.getString(4) ?: Types.EXPENSE, category = c.getString(5) ?: "Другое", merchant = c.getString(6) ?: "",
        merchantRu = c.getString(7) ?: "", note = c.getString(8) ?: "", card = c.getString(9) ?: "",
        account = c.getString(10) ?: "", src = c.getInt(11), items = itemsFromJson(c.getString(12)),
        raw = c.getString(13) ?: "", hash = c.getString(14) ?: "", installments = c.getInt(15),
        grp = c.getString(16) ?: "", grpTotal = c.getDouble(17)
    )

    private val cols = "id,ts,amount,currency,type,category,merchant,merchantRu,note,card,account,src,items,raw,hash,installments,grp,grpTotal"

    fun query(where: String = "1", args: Array<String> = emptyArray(), order: String = "ts DESC"): List<Tx> =
        readableDatabase.rawQuery("SELECT $cols FROM tx WHERE $where ORDER BY $order", args).use { c ->
            val l = mutableListOf<Tx>(); while (c.moveToNext()) l += read(c); l
        }

    fun between(from: Long, to: Long) = query("ts>=? AND ts<?", arrayOf(from.toString(), to.toString()))
    fun byId(id: Long) = query("id=?", arrayOf(id.toString())).firstOrNull()
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM tx", null).use { it.moveToFirst(); it.getInt(0) }

    fun insert(t: Tx): Long { val id = writableDatabase.insert("tx", null, cv(t)); t.id = id; return id }
    fun update(t: Tx) { writableDatabase.update("tx", cv(t), "id=?", arrayOf(t.id.toString())) }
    fun delete(id: Long) { writableDatabase.delete("tx", "id=?", arrayOf(id.toString())) }

    fun seen(hash: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM seen WHERE hash=?", arrayOf(hash)).use { it.moveToFirst() }
    fun markSeen(hash: String) { writableDatabase.insertWithOnConflict("seen", null, ContentValues().apply { put("hash", hash) }, SQLiteDatabase.CONFLICT_IGNORE) }

    /**
     * Adds a transaction or merges it into the same purchase already known from another channel
     * (e.g. Cal SMS + Cal push + Wallet push + statement line). Returns the stored tx and whether it was new.
     */
    @Synchronized
    fun addOrMerge(t: Tx): Pair<Tx, Boolean> {
        if (t.hash.isNotEmpty()) {
            if (seen(t.hash)) return t to false
            markSeen(t.hash)
        }
        val wide = t.src and (Src.STATEMENT or Src.RECEIPT or Src.MANUAL or Src.PLAN) != 0
        val maxWindow = 5 * DAY
        // an expense may also be the full-price purchase of an installment plan (type plan)
        val types = if (t.type == Types.EXPENSE) "type IN ('${Types.EXPENSE}','${Types.PLAN}')" else "type='${t.type}'"
        val cands = query(
            "$types AND currency=? AND ts BETWEEN ? AND ? AND (src & ?)=0",
            arrayOf(t.currency, (t.ts - maxWindow).toString(), (t.ts + maxWindow).toString(), t.src.toString())
        ).filter { c ->
            val w = when {
                wide || c.src and Src.PLAN != 0 -> if (c.src and Src.PLAN != 0 || t.src and Src.PLAN != 0) 5 * DAY else 3 * DAY
                c.src and (Src.STATEMENT or Src.RECEIPT or Src.MANUAL) != 0 -> 3 * DAY
                else -> DAY + DAY / 2
            }
            abs(c.ts - t.ts) <= w
        }
        val single = cands.filter { abs(it.amount - t.amount) < 0.005 }
            .filter {
                when {
                    it.grp.isEmpty() -> true
                    // installment payment: only a statement line or a bank-account debit can confirm it, never a new card purchase
                    it.grp.startsWith("plan:") && !it.grp.endsWith(":0") -> t.src and Src.STATEMENT != 0 || t.card.isEmpty()
                    it.grp.startsWith("plan:") -> true
                    else -> false
                }
            }
            .filter { it.card.isEmpty() || t.card.isEmpty() || it.card == t.card }
            .minByOrNull { abs(it.ts - t.ts) }
        if (single != null) {
            single.src = single.src or t.src
            if (single.merchant.isEmpty() || (t.src == Src.STATEMENT && t.merchant.isNotEmpty())) {
                if (t.merchant.isNotEmpty()) single.merchant = t.merchant
            }
            if (single.card.isEmpty()) single.card = t.card
            if (single.account.isEmpty()) single.account = t.account
            if (single.items.isEmpty()) single.items = t.items
            if (single.merchantRu.isEmpty()) single.merchantRu = t.merchantRu
            if (t.src and (Src.SMS or Src.NOTIF_CAL or Src.NOTIF_WALLET) != 0 && single.src and Src.STATEMENT == 0) single.ts = t.ts
            if (t.src and Src.STATEMENT != 0 && single.src and Src.PLAN != 0) single.amount = t.amount
            if (t.note.isNotEmpty() && !single.note.contains(t.note)) single.note = listOf(single.note, t.note).filter { it.isNotEmpty() }.joinToString("; ")
            single.raw = listOf(single.raw, t.raw).filter { it.isNotEmpty() }.joinToString("\n———\n").take(4000)
            update(single); changed()
            return single to false
        }
        // Split receipt groups (one card payment split into several categories)
        val groups = cands.filter { it.grp.isNotEmpty() && !it.grp.startsWith("plan:") && abs(it.grpTotal - t.amount) < 0.005 }.groupBy { it.grp }
        groups.values.firstOrNull()?.let { g ->
            g.forEach { it.src = it.src or t.src; if (it.card.isEmpty()) it.card = t.card; update(it) }
            changed(); return g.first() to false
        }
        insert(t); changed()
        return t to true
    }

    // ---------- rules / dictionary ----------
    fun rule(mkey: String): Pair<String, String>? =
        readableDatabase.rawQuery("SELECT merchantRu, category FROM rules WHERE mkey=?", arrayOf(mkey)).use {
            if (it.moveToFirst()) (it.getString(0) ?: "") to (it.getString(1) ?: "") else null
        }

    fun setRule(mkey: String, ru: String?, cat: String?) {
        if (mkey.isEmpty()) return
        val old = rule(mkey)
        writableDatabase.insertWithOnConflict("rules", null, ContentValues().apply {
            put("mkey", mkey); put("merchantRu", ru ?: old?.first ?: ""); put("category", cat ?: old?.second ?: "")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allRules(): List<Triple<String, String, String>> =
        readableDatabase.rawQuery("SELECT mkey, merchantRu, category FROM rules ORDER BY mkey", null).use { c ->
            val l = mutableListOf<Triple<String, String, String>>(); while (c.moveToNext()) l += Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""); l
        }

    fun word(heb: String): String? =
        readableDatabase.rawQuery("SELECT rus FROM words WHERE heb=?", arrayOf(heb)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setWord(heb: String, rus: String) {
        writableDatabase.insertWithOnConflict("words", null, ContentValues().apply { put("heb", heb); put("rus", rus) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- balances ----------
    fun setBalance(acc: String, amount: Double, ts: Long) {
        writableDatabase.insertWithOnConflict("balances", null, ContentValues().apply {
            put("account", acc); put("amount", amount); put("ts", ts)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun balances(): List<Triple<String, Double, Long>> =
        readableDatabase.rawQuery("SELECT account, amount, ts FROM balances ORDER BY account", null).use { c ->
            val l = mutableListOf<Triple<String, Double, Long>>(); while (c.moveToNext()) l += Triple(c.getString(0), c.getDouble(1), c.getLong(2)); l
        }

    // ---------- unparsed messages (for review) ----------
    fun addUnparsed(source: String, sender: String, text: String, ts: Long) {
        writableDatabase.insertWithOnConflict("unparsed", null, ContentValues().apply {
            put("ts", ts); put("source", source); put("sender", sender); put("text", text.take(2000)); put("hash", (source + text).hashCode().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    data class Unparsed(val id: Long, val ts: Long, val source: String, val sender: String, val text: String)

    fun unparsed(): List<Unparsed> =
        readableDatabase.rawQuery("SELECT id, ts, source, sender, text FROM unparsed ORDER BY ts DESC LIMIT 200", null).use { c ->
            val l = mutableListOf<Unparsed>(); while (c.moveToNext()) l += Unparsed(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3) ?: "", c.getString(4)); l
        }

    fun deleteUnparsed(id: Long) { writableDatabase.delete("unparsed", "id=?", arrayOf(id.toString())); changed() }

    // ---------- backup ----------
    fun exportJson(): JSONObject {
        val root = JSONObject().put("version", 1).put("exported", System.currentTimeMillis())
        val txs = JSONArray()
        query(order = "ts").forEach { t ->
            txs.put(JSONObject().put("ts", t.ts).put("amount", t.amount).put("currency", t.currency).put("type", t.type)
                .put("category", t.category).put("merchant", t.merchant).put("merchantRu", t.merchantRu).put("note", t.note)
                .put("card", t.card).put("account", t.account).put("src", t.src).put("items", JSONArray(itemsToJson(t.items)))
                .put("raw", t.raw).put("hash", t.hash).put("installments", t.installments).put("grp", t.grp).put("grpTotal", t.grpTotal))
        }
        root.put("tx", txs)
        root.put("rules", JSONArray().also { a -> allRules().forEach { a.put(JSONArray().put(it.first).put(it.second).put(it.third)) } })
        root.put("balances", JSONArray().also { a -> balances().forEach { a.put(JSONArray().put(it.first).put(it.second).put(it.third)) } })
        val words = JSONArray()
        readableDatabase.rawQuery("SELECT heb, rus FROM words", null).use { c -> while (c.moveToNext()) words.put(JSONArray().put(c.getString(0)).put(c.getString(1))) }
        root.put("words", words)
        val seen = JSONArray()
        readableDatabase.rawQuery("SELECT hash FROM seen", null).use { c -> while (c.moveToNext()) seen.put(c.getString(0)) }
        root.put("seen", seen)
        root.put("plans", JSONArray().also { a -> Plans.all(this).forEach { a.put(Plans.toJson(it)) } })
        return root
    }

    fun importJson(root: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("tx", "rules", "balances", "words", "seen", "plans").forEach { db.delete(it, null, null) }
            val txs = root.getJSONArray("tx")
            for (i in 0 until txs.length()) {
                val o = txs.getJSONObject(i)
                insert(Tx(
                    ts = o.getLong("ts"), amount = o.getDouble("amount"), currency = o.optString("currency", "ILS"),
                    type = o.optString("type", Types.EXPENSE), category = o.optString("category"), merchant = o.optString("merchant"),
                    merchantRu = o.optString("merchantRu"), note = o.optString("note"), card = o.optString("card"),
                    account = o.optString("account"), src = o.optInt("src"), items = itemsFromJson(o.optJSONArray("items")?.toString()),
                    raw = o.optString("raw"), hash = o.optString("hash"), installments = o.optInt("installments"),
                    grp = o.optString("grp"), grpTotal = o.optDouble("grpTotal", 0.0)
                ))
            }
            root.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { setRule(it.getString(0), it.getString(1), it.getString(2)) } }
            root.optJSONArray("balances")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let {
                db.insertWithOnConflict("balances", null, ContentValues().apply { put("account", it.getString(0)); put("amount", it.getDouble(1)); put("ts", it.getLong(2)) }, SQLiteDatabase.CONFLICT_REPLACE)
            } }
            root.optJSONArray("words")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { setWord(it.getString(0), it.getString(1)) } }
            root.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) markSeen(a.getString(i)) }
            root.optJSONArray("plans")?.let { a -> for (i in 0 until a.length()) Plans.insertRaw(this, Plans.fromJson(a.getJSONObject(i))) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
}

package com.shakhov.finance

data class Item(
    var name: String,
    var nameRu: String = "",
    var price: Double,
    var sub: String = "",
    var category: String = ""
)

data class Tx(
    var id: Long = 0,
    var ts: Long,
    var amount: Double,
    var currency: String = "ILS",
    var type: String = Types.EXPENSE,
    var category: String = "Другое",
    var merchant: String = "",
    var merchantRu: String = "",
    var note: String = "",
    var card: String = "",
    var account: String = "",
    var src: Int = 0,
    var items: List<Item> = emptyList(),
    var raw: String = "",
    var hash: String = "",
    var installments: Int = 0,
    var grp: String = "",
    var grpTotal: Double = 0.0
)

object Types {
    const val EXPENSE = "expense"
    const val INCOME = "income"
    const val TRANSFER = "transfer"
    /** Full price of an installment purchase: shown, but not counted — monthly payments are counted instead. */
    const val PLAN = "plan"
    /** Money put aside into a savings goal (копилка) — not an expense, but no longer free to spend. */
    const val SAVE = "save"
}

object Src {
    const val SMS = 1
    const val NOTIF_WALLET = 2
    const val NOTIF_CAL = 4
    const val NOTIF_OTHER = 8
    const val STATEMENT = 16
    const val RECEIPT = 32
    const val MANUAL = 64
    const val SEED = 128
    const val PLAN = 256

    fun label(mask: Int): String {
        val l = mutableListOf<String>()
        if (mask and SMS != 0) l += "СМС"
        if (mask and NOTIF_WALLET != 0) l += "Кошелёк"
        if (mask and NOTIF_CAL != 0) l += "Cal"
        if (mask and NOTIF_OTHER != 0) l += "Уведомление"
        if (mask and STATEMENT != 0) l += "Выписка"
        if (mask and RECEIPT != 0) l += "Чек"
        if (mask and MANUAL != 0) l += "Вручную"
        if (mask and SEED != 0) l += "Старая таблица"
        if (mask and PLAN != 0) l += "График рассрочки"
        return l.joinToString(" + ")
    }
}

object Cats {
    data class Custom(val name: String, val icon: String, val type: String)

    val defaultExpense = listOf(
        "Продукты", "Кафе и рестораны", "Покупки онлайн", "Подписки", "Транспорт и авто",
        "Дом и счета", "Здоровье", "Дети", "Концерты", "Табак", "Алкоголь", "Хозтовары",
        "Гигиена", "Одежда", "Алименты", "Наличные", "Другое"
    )
    val defaultIncome = listOf("Зарплата", "Поступления", "Возврат")
    val transfer = listOf("Погашение карты", "Перевод между счетами")

    private val icons = mapOf(
        "Продукты" to "🛒", "Кафе и рестораны" to "☕", "Покупки онлайн" to "📦", "Подписки" to "🔁", "Транспорт и авто" to "⛽",
        "Дом и счета" to "🏠", "Здоровье" to "💊", "Дети" to "🧸", "Концерты" to "🎸", "Табак" to "💨", "Алкоголь" to "🍷",
        "Хозтовары" to "🧽", "Гигиена" to "🧴", "Одежда" to "👕", "Алименты" to "👨‍👧‍👦", "Наличные" to "💵", "Другое" to "•",
        "Зарплата" to "💼", "Поступления" to "⬇", "Возврат" to "↩", "Погашение карты" to "💳", "Перевод между счетами" to "↔"
    )

    @Volatile var custom: List<Custom> = emptyList()
    @Volatile var hidden: Set<String> = emptySet()

    val expense: List<String> get() = (defaultExpense.filter { it !in hidden || it == "Другое" }
        .dropLast(1) + custom.filter { it.type == Types.EXPENSE }.map { it.name } + "Другое").distinct()
    val income: List<String> get() = (defaultIncome.filter { it !in hidden } + custom.filter { it.type == Types.INCOME }.map { it.name }).distinct()

    fun forType(t: String) = when (t) {
        Types.INCOME -> income
        Types.TRANSFER -> transfer
        else -> expense
    }
    fun all() = expense + income + transfer
    fun icon(name: String): String = icons[name] ?: custom.firstOrNull { it.name == name }?.icon?.takeIf { it.isNotBlank() } ?: name.take(1)
    fun isDefault(name: String) = name in defaultExpense || name in defaultIncome || name in transfer

    // ---- storage (prefs) ----
    fun load(ctx: android.content.Context) {
        val p = Prefs.get(ctx)
        val a = org.json.JSONArray(p.getString("cats_custom", "[]"))
        custom = (0 until a.length()).map { a.getJSONObject(it).let { o -> Custom(o.getString("name"), o.optString("icon"), o.optString("type", Types.EXPENSE)) } }
        hidden = p.getStringSet("cats_hidden", emptySet())!!.toSet()
    }

    private fun save(ctx: android.content.Context) {
        val a = org.json.JSONArray()
        custom.forEach { a.put(org.json.JSONObject().put("name", it.name).put("icon", it.icon).put("type", it.type)) }
        Prefs.get(ctx).edit().putString("cats_custom", a.toString()).putStringSet("cats_hidden", hidden).apply()
    }

    fun add(ctx: android.content.Context, name: String, icon: String, type: String = Types.EXPENSE): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n in all()) { if (n in hidden) { hidden = hidden - n; save(ctx); return true }; return false }
        custom = custom + Custom(n, icon.trim(), type); save(ctx); return true
    }

    /** Renames everywhere: operations, shop rules, receipt items, limits, savings goals. */
    fun rename(ctx: android.content.Context, old: String, new: String, icon: String) {
        val n = new.trim(); if (n.isEmpty()) return
        val db = Db.get(ctx)
        if (old != n) {
            db.writableDatabase.execSQL("UPDATE tx SET category=? WHERE category=?", arrayOf(n, old))
            db.writableDatabase.execSQL("UPDATE rules SET category=? WHERE category=?", arrayOf(n, old))
            db.writableDatabase.execSQL("UPDATE plans SET category=? WHERE category=?", arrayOf(n, old))
            Budget.renameCategory(ctx, old, n)
        }
        if (isDefault(old)) {
            if (old != n) { hidden = hidden + old; custom = custom + Custom(n, icon, Types.EXPENSE) }
        } else custom = custom.map { if (it.name == old) it.copy(name = n, icon = icon) else it }
        save(ctx); db.changed(); Backup.schedule(ctx)
    }

    /** Deletes a category; its operations move to «Другое». */
    fun delete(ctx: android.content.Context, name: String) {
        if (name == "Другое") return
        val db = Db.get(ctx)
        db.writableDatabase.execSQL("UPDATE tx SET category='Другое' WHERE category=?", arrayOf(name))
        db.writableDatabase.execSQL("UPDATE rules SET category='Другое' WHERE category=?", arrayOf(name))
        if (isDefault(name)) hidden = hidden + name else custom = custom.filter { it.name != name }
        Budget.renameCategory(ctx, name, null)
        save(ctx); db.changed(); Backup.schedule(ctx)
    }
}

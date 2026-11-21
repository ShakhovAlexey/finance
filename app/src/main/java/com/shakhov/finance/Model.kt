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
    val expense = listOf(
        "Продукты", "Кафе и рестораны", "Покупки онлайн", "Подписки", "Транспорт и авто",
        "Дом и счета", "Здоровье", "Дети", "Концерты", "Табак", "Алкоголь", "Хозтовары",
        "Гигиена", "Одежда", "Наличные", "Другое"
    )
    val income = listOf("Зарплата", "Поступления", "Возврат")
    val transfer = listOf("Погашение карты", "Перевод между счетами")
    fun forType(t: String) = when (t) {
        Types.INCOME -> income
        Types.TRANSFER -> transfer
        else -> expense
    }
    fun all() = expense + income + transfer
}

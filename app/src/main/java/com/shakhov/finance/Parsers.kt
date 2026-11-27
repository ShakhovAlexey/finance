package com.shakhov.finance

import java.util.Calendar

/** Pure text parsing (no Android dependencies) — covered by unit tests. */
object Parsers {

    enum class Kind { PURCHASE, CASH, SALARY, CREDIT, DEBIT, CHECK, CARD_BILL, REFUND }

    data class Parsed(
        val type: String,
        val kind: Kind,
        val amount: Double,
        val currency: String,
        val day: Int?,
        val month: Int?,
        val merchant: String,
        val card: String,
        val account: String,
        val note: String,
        val installments: Int = 0,
        val bank: String
    )

    data class Balance(val account: String, val amount: Double)

    sealed class Result {
        data class T(val p: Parsed) : Result()
        data class B(val b: Balance) : Result()
    }

    private val AMOUNT = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    private val reSum = Regex("בסך\\s*:?\\s*$AMOUNT\\s*(ש\"ח|ש״ח|שח|₪|ILS|דולר|\\$|USD|אירו|€|EUR)?")
    private val reBalance = Regex("יתרת\\s+(?:חשבונך|החשבון|העו\"ש)[^\\d\\-]*(-?)\\s*$AMOUNT")
    private val reAccount = Regex("(\\d{2,3}-\\d{3,7})")
    private val reCard = Regex("המסתיים\\s*ב\\s*-?\\s*(\\d{4})")
    private val reDate = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?![\\d/])")
    private val reDateWord = Regex("ב\\s*-?\\s*\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?")
    private val reCheckNo = Regex("שמספרו\\s*(\\d+)")
    private val reCalPush = Regex("בוצעה עסקה\\s*ב-?\\s*(.+?)\\s+בסך\\s*₪?\\s*$AMOUNT\\s*(?:ש\"ח|₪)?\\s*בכרטיס[^\\d]{0,30}(\\d{4})")

    /** Same shop written two ways (Cal SMS vs statement, old table vs statement)? Empty name is compatible with anything. */
    fun sameMerchant(a: String, b: String): Boolean {
        val ka = merchantKey(a); val kb = merchantKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return true
        if (ka.startsWith(kb.take(4)) || kb.startsWith(ka.take(4))) return true
        val wa = ka.split(" ").filter { it.length >= 3 }.toSet()
        return kb.split(" ").any { it.length >= 3 && it in wa }
    }

    private val reCheckWord = Regex("(?<![\\u05D0-\\u05EA])(?:שיק|צ'ק|צ׳ק)(?![\\u05D0-\\u05EA])")
    private val reUrl = Regex("https?://\\S+")
    private val adWords = listOf("להסרה", "הסרה מרשימת", "לשלוח הסר", "הסר ל", "בתקנון", "מצטרפים", "הטבה", "הטבת", "מבצע", "בכפוף ל", "קופון", "הגרלה")

    /** Advertising / marketing SMS: unsubscribe text, promo words, foreign links or simply too long. */
    fun isAd(flat: String): Boolean {
        if (adWords.any { flat.contains(it) }) return true
        if (reUrl.findAll(flat).any { u -> listOf("cal-online", "bankhapoalim", "poalim", "isracard", "max.co.il", "leumi").none { u.value.contains(it) } }) return true
        return flat.length > 600
    }

    private val paymentCues = listOf(
        "חויב", "חיוב", "בוצעה", "עסקה", "תשלום", "שילמת", "רכישה", "משיכה", "זיכוי", "העברה",
        "payment", "paid", "purchase", "charged", "transaction", "оплат", "списан", "покупк", "платёж", "платеж"
    )

    /** Notification text actually talks about a payment (not just mentions a price). */
    fun looksLikePayment(flat: String): Boolean {
        val l = flat.lowercase()
        return paymentCues.any { l.contains(it) }
    }

    private val rePayments = Regex("(\\d+)\\s*תשלומים")

    fun clean(s: String): String =
        s.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .replace(' ', ' ')
            .replace('״', '"')
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    fun num(s: String): Double = s.replace(",", "").toDouble()

    private fun currency(sym: String?): String = when (sym) {
        "דולר", "$", "USD" -> "USD"
        "אירו", "€", "EUR" -> "EUR"
        else -> "ILS"
    }

    private val declined = listOf("נדחתה", "לא אושרה", "סירוב", "סורבה", "בוטלה", "ביטול עסקה")

    fun parse(textIn: String): Result? {
        val text = clean(textIn)
        if (text.isEmpty()) return null
        val flat = text.replace('\n', ' ')

        // Balance notice
        reBalance.find(flat)?.let { m ->
            val acc = reAccount.find(flat)?.groupValues?.get(1) ?: ""
            val v = num(m.groupValues[2]) * if (m.groupValues[1] == "-") -1 else 1
            return Result.B(Balance(acc, v))
        }

        // Cal app push: "בוצעה עסקה ב-סטופ בסך ₪55 בכרטיס ויזה 1234"
        reCalPush.find(flat)?.let { m ->
            if (declined.any { flat.contains(it) }) return null
            return Result.T(Parsed(type = Types.EXPENSE, kind = Kind.PURCHASE, amount = num(m.groupValues[2]), currency = "ILS",
                day = null, month = null, merchant = m.groupValues[1].trim(), card = m.groupValues[3], account = "", note = "", bank = "Cal"))
        }
        val sum = reSum.find(flat) ?: return null
        if (declined.any { flat.contains(it) }) return null
        if (isAd(flat)) return null
        val amount = num(sum.groupValues[1])
        val cur = currency(sum.groupValues.getOrNull(2))
        val date = reDate.find(flat)
        val day = date?.groupValues?.get(1)?.toIntOrNull()
        val month = date?.groupValues?.get(2)?.toIntOrNull()
        val validDate = day != null && month != null && day in 1..31 && month in 1..12

        val isCal = flat.contains("כאל") || flat.contains("cal-online") || reCard.containsMatchIn(flat)
        if (isCal && reCard.containsMatchIn(flat)) {
            val card = reCard.find(flat)!!.groupValues[1]
            val kind = when {
                flat.contains("משיכת מזומן") -> Kind.CASH
                flat.contains("זיכוי") -> Kind.REFUND
                else -> Kind.PURCHASE
            }
            val pays = if (flat.contains("תשלומים")) rePayments.find(flat)?.groupValues?.get(1)?.toIntOrNull() ?: -1 else 0
            val merchant = if (kind == Kind.CASH) "" else calMerchant(flat)
            return Result.T(
                Parsed(
                    type = if (kind == Kind.REFUND) Types.INCOME else Types.EXPENSE,
                    kind = kind, amount = amount, currency = cur,
                    day = if (validDate) day else null, month = if (validDate) month else null,
                    merchant = merchant, card = card, account = "",
                    note = buildString {
                        if (flat.contains("אינטרנט") || flat.contains("טלפון")) append("интернет/телефон")
                        if (pays != 0) { if (isNotEmpty()) append("; "); append(if (pays > 0) "рассрочка, $pays платежей" else "рассрочка") }
                    },
                    installments = pays, bank = "Cal"
                )
            )
        }

        // Bank (Hapoalim and similar): only messages about an account
        val acc = reAccount.find(flat)?.groupValues?.get(1) ?: ""
        if (acc.isEmpty() && !flat.contains("חשבונך") && !flat.contains("לחשבון")) return null
        val cardBill = listOf("כאל", "ויזה", "ישראכרט", "לאומי קארד", "אמריקן אקספרס", "דיינרס", "כרטיס אשראי")
        val (type, kind) = when {
            flat.contains("משכורת") -> Types.INCOME to Kind.SALARY
            reCheckWord.containsMatchIn(flat) -> Types.EXPENSE to Kind.CHECK
            flat.contains("משיכת מזומן") -> Types.EXPENSE to Kind.CASH
            flat.contains("זוכה") || flat.contains("זיכוי") || flat.contains("הועבר לחשבונך") || flat.contains("הופקד") ->
                Types.INCOME to Kind.CREDIT
            flat.contains("חויב") || flat.contains("חיוב") || flat.contains("הוראת קבע") || flat.contains("הועבר מחשבונך") || flat.contains("העברה") ->
                if (cardBill.any { flat.contains(it) } && !Max.isMax(flat)) Types.TRANSFER to Kind.CARD_BILL else Types.EXPENSE to Kind.DEBIT
            else -> return null
        }
        val note = when (kind) {
            Kind.CHECK -> reCheckNo.find(flat)?.let { "чек №${it.groupValues[1]}" } ?: "чек"
            Kind.DEBIT -> if (flat.contains("הוראת קבע")) "постоянное поручение" else ""
            else -> ""
        }
        val merchant = if (kind == Kind.DEBIT) bankMerchant(flat) else ""
        return Result.T(
            Parsed(
                type = type, kind = kind, amount = amount, currency = cur,
                day = if (validDate) day else null, month = if (validDate) month else null,
                merchant = merchant, card = "", account = acc, note = note, bank = "Банк"
            )
        )
    }

    /** Merchant in Cal messages: text between the later of (date phrase, card phrase) and "בסך". */
    fun calMerchant(flat: String): String {
        val sumIdx = flat.indexOf("בסך")
        if (sumIdx < 0) return ""
        val prefix = flat.substring(0, sumIdx)
        var start = 0
        reCard.findAll(prefix).forEach { start = maxOf(start, it.range.last + 1) }
        reDateWord.findAll(prefix).forEach { start = maxOf(start, it.range.last + 1) }
        var seg = prefix.substring(start).trim().trimEnd(',', '.')
        if (seg.startsWith("ב")) {
            seg = seg.substring(1).trimStart('-', ' ')
        } else {
            val i = Regex("(?:^|\\s)ב-?(?=\\S)").findAll(seg).lastOrNull()
            seg = if (i != null) seg.substring(i.range.last + 1) else ""
        }
        return seg.replace(Regex("\\s*ב?-?\\s*\\d+\\s*תשלומים.*$"), "").trim().trimEnd(',', '.')
    }

    private fun bankMerchant(flat: String): String {
        val m = Regex("(?:לטובת|ל-|עבור)\\s*([^,.]{2,40})").find(flat)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    // ---------- Notifications (Google Wallet and others) ----------
    private val reShekel = Regex("(?:₪\\s?(\\d[\\d,]*(?:[.,]\\d{1,2})?))|(?:(\\d[\\d,]*(?:[.,]\\d{1,2})?)\\s?(?:₪|ש\"ח|שח|ILS))")
    private val reForeign = Regex("(?:([\\$€])\\s?(\\d[\\d,]*(?:[.,]\\d{1,2})?))|(?:(\\d[\\d,]*(?:[.,]\\d{1,2})?)\\s?(USD|EUR|\\$|€))")
    private val reDots = Regex("(?:•+|\\*+|·+|…)\\s?(\\d{4})")

    fun looseNumber(s: String): Double {
        val t = s.trim()
        return if (Regex(".*\\d,\\d{1,2}$").matches(t) && !t.contains('.')) {
            t.substring(0, t.lastIndexOf(',')).replace(",", "").replace(".", "")
                .plus(".").plus(t.substring(t.lastIndexOf(',') + 1)).toDouble()
        } else t.replace(",", "").toDouble()
    }

    /** Generic parse for payment notifications: merchant = title, amount with currency in text. */
    fun parseWallet(title: String, text: String): Parsed? {
        val all = clean("$title $text")
        if (declined.any { all.contains(it) }) return null
        val lower = all.lowercase()
        if (listOf("declined", "отклон", "не прошл", "failed").any { lower.contains(it) }) return null
        var amount: Double? = null
        var cur = "ILS"
        reShekel.find(all)?.let { m ->
            amount = looseNumber(m.groupValues[1].ifEmpty { m.groupValues[2] })
        }
        if (amount == null) reForeign.find(all)?.let { m ->
            val sym = m.groupValues[1].ifEmpty { m.groupValues[4] }
            amount = looseNumber(m.groupValues[2].ifEmpty { m.groupValues[3] })
            cur = if (sym == "€" || sym == "EUR") "EUR" else "USD"
        }
        val a = amount ?: return null
        if (a <= 0) return null
        val card = reDots.find(all)?.groupValues?.get(1) ?: ""
        val isRefund = listOf("refund", "возврат", "החזר", "זיכוי").any { lower.contains(it) }
        var merchant = clean(title)
        if (reShekel.containsMatchIn(merchant) || merchant.length > 60) merchant = ""
        val generic = listOf("בוצעה עסקה", "עסקה חדשה", "עסקה", "תשלום", "חיוב", "payment", "оплата", "покупка", "транзакция")
        if (generic.any { merchant.lowercase().trim().startsWith(it) }) merchant = ""
        return Parsed(
            type = if (isRefund) Types.INCOME else Types.EXPENSE,
            kind = if (isRefund) Kind.REFUND else Kind.PURCHASE,
            amount = a, currency = cur, day = null, month = null,
            merchant = merchant, card = card, account = "", note = "", bank = "Кошелёк"
        )
    }

    /** Builds the timestamp: uses message time, but if the text names another day, that day at 12:00. */
    fun resolveTs(p: Parsed, msgTs: Long): Long {
        if (p.day == null || p.month == null) return msgTs
        val c = Calendar.getInstance().apply { timeInMillis = msgTs }
        if (c.get(Calendar.DAY_OF_MONTH) == p.day && c.get(Calendar.MONTH) + 1 == p.month) return msgTs
        val r = Calendar.getInstance().apply {
            timeInMillis = msgTs
            set(Calendar.MONTH, p.month - 1); set(Calendar.DAY_OF_MONTH, p.day)
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (r.timeInMillis > msgTs + 2 * 86400000L) r.add(Calendar.YEAR, -1)
        return r.timeInMillis
    }

    // ---------- Receipts (OCR text) ----------
    data class ReceiptLine(val name: String, val price: Double)
    data class Receipt(val items: List<ReceiptLine>, val total: Double?)

    private val rePrice = Regex("(-?\\d{1,4}[.,]\\d{2})(?!\\d)")
    private val skipWords = listOf(
        "מע\"מ", "מעמ", "עודף", "אשראי", "כרטיס", "תאריך", "קופה", "קופאי", "ח.פ", "עוסק", "טל", "טלפון",
        "חשבונית", "מס'", "שעה", "אסמכתא", "אישור", "מזומן", "ויזה", "visa", "מסוף", "עסקה", "תודה", "פריטים"
    )
    private val totalWords = listOf("סה\"כ", "סהכ", "סה'כ", "לתשלום", "total", "סך הכל")

    fun parseReceipt(text: String): Receipt {
        val items = mutableListOf<ReceiptLine>()
        var total: Double? = null
        for (raw in text.lines()) {
            val line = clean(raw)
            if (line.length < 3) continue
            val prices = rePrice.findAll(line).toList()
            if (prices.isEmpty()) continue
            val lower = line.lowercase()
            if (totalWords.any { lower.contains(it) }) {
                total = prices.maxOf { looseNumber(it.groupValues[1].replace(",", ".")) }
                continue
            }
            if (skipWords.any { lower.contains(it) }) continue
            val price = looseNumber(prices.last().groupValues[1].replace(",", "."))
            var name = line
            prices.forEach { name = name.replace(it.value, " ") }
            name = name.replace(Regex("\\d{6,}"), " ")
                .replace(Regex("[*#|_=~]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val isDiscount = price < 0 || lower.contains("הנחה") || lower.contains("מבצע")
            if (isDiscount && items.isNotEmpty()) {
                val last = items.removeAt(items.lastIndex)
                items += last.copy(price = Math.round((last.price - kotlin.math.abs(price)) * 100) / 100.0)
                continue
            }
            if (!name.any { it.isLetter() }) continue
            if (price <= 0 || price > 5000) continue
            items += ReceiptLine(name, price)
        }
        return Receipt(items, total)
    }

    // ---------- Hebrew → Cyrillic transliteration (placeholder until the user edits) ----------
    private val tr = mapOf(
        'א' to "а", 'ב' to "б", 'ג' to "г", 'ד' to "д", 'ה' to "а", 'ו' to "о", 'ז' to "з", 'ח' to "х",
        'ט' to "т", 'י' to "и", 'כ' to "к", 'ך' to "х", 'ל' to "л", 'מ' to "м", 'ם' to "м", 'נ' to "н",
        'ן' to "н", 'ס' to "с", 'ע' to "а", 'פ' to "п", 'ף' to "ф", 'צ' to "ц", 'ץ' to "ц", 'ק' to "к",
        'ר' to "р", 'ש' to "ш", 'ת' to "т"
    )

    fun hasHebrew(s: String) = s.any { it in '֐'..'׿' }

    fun translit(s: String): String {
        val sb = StringBuilder()
        var wordStart = true
        for (ch in s) {
            val t = tr[ch]
            if (t != null) {
                sb.append(if (wordStart) t.replaceFirstChar { it.uppercase() } else t)
                wordStart = false
            } else {
                sb.append(ch); wordStart = !ch.isLetterOrDigit()
            }
        }
        return sb.toString()
    }

    fun merchantKey(m: String): String =
        clean(m).lowercase().replace(Regex("[*#].*$"), "").replace(Regex("\\d{3,}"), "")
            .replace(Regex("[^\\p{L}\\d ]"), " ").replace(Regex("\\s+"), " ").trim()
}

/** Max card holds only standing payments (car insurance, health fund, arnona) and is not tracked item by item,
 *  so its monthly debit from the bank account is the expense itself — not a card-bill transfer like Cal. */
object Max {
    const val RU = "Max: страховка авто, больничная касса, арнона"
    fun isMax(s: String) = Regex("(?<![\\u05D0-\\u05EA])מקס(?![\\u05D0-\\u05EA])").containsMatchIn(s) || s.contains("max.co.il", true)
    fun apply(t: Tx) {
        if (!isMax(t.merchant) && !isMax(t.raw)) return
        if (t.type == Types.TRANSFER) t.type = Types.EXPENSE
        if (t.type == Types.EXPENSE) { t.category = "Дом и счета"; if (t.merchant.isEmpty()) t.merchant = "מקס"; t.merchantRu = RU }
    }
}

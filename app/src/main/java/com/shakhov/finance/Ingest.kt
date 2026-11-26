package com.shakhov.finance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs

/** Russian names + categories: user rules first, then built-in dictionary, then transliteration. */
object Dict {
    private var words: Map<String, String> = emptyMap()
    private var itemCat: Map<String, List<String>> = emptyMap()
    private var merchCat: Map<String, List<String>> = emptyMap()
    private var loaded = false

    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        runCatching {
            val o = JSONObject(ctx.assets.open("dict.json").bufferedReader().readText())
            words = o.getJSONObject("words").let { w -> w.keys().asSequence().associateWith { w.getString(it) } }
            fun lists(n: String) = o.getJSONObject(n).let { m ->
                m.keys().asSequence().associateWith { k -> m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it).lowercase() } } }
            }
            itemCat = lists("itemCategory"); merchCat = lists("merchantCategory")
        }
        loaded = true
    }

    fun merchantCategory(ctx: Context, merchant: String): String? {
        load(ctx)
        val m = merchant.lowercase()
        if (m.isBlank()) return null
        return merchCat.entries.firstOrNull { (_, kws) -> kws.any { it.isNotBlank() && m.contains(it) } }?.key
    }

    fun itemCategory(ctx: Context, name: String, default: String = "Продукты"): String {
        load(ctx)
        val n = name.lowercase()
        return itemCat.entries.firstOrNull { (_, kws) -> kws.any { it.isNotBlank() && n.contains(it) } }?.key ?: default
    }

    /** Word-by-word translation of a Hebrew item name; unknown words transliterated. */
    fun translate(ctx: Context, heb: String): String {
        load(ctx)
        if (!Parsers.hasHebrew(heb)) return heb
        val db = Db.get(ctx)
        db.word(heb.trim())?.let { return it }
        words[heb.trim()]?.let { return it }
        return heb.split(Regex("\\s+")).joinToString(" ") { w ->
            val core = w.trim('.', ',', '\'', '"', '(', ')')
            db.word(core) ?: words[core] ?: words[core.removePrefix("ה")] ?: if (Parsers.hasHebrew(core)) Parsers.translit(core) else w
        }
    }

    /** Fills category + Russian name for a fresh transaction. */
    fun enrich(ctx: Context, t: Tx, kind: Parsers.Kind?) {
        val db = Db.get(ctx)
        val key = Parsers.merchantKey(t.merchant)
        val rule = if (key.isNotEmpty()) db.rule(key) else null
        if (t.merchantRu.isEmpty()) t.merchantRu = rule?.first?.takeIf { it.isNotEmpty() }
            ?: if (Parsers.hasHebrew(t.merchant)) translate(ctx, t.merchant) else ""
        t.category = when {
            rule != null && rule.second.isNotEmpty() -> rule.second
            kind == Parsers.Kind.SALARY -> "Зарплата"
            kind == Parsers.Kind.CREDIT -> "Поступления"
            kind == Parsers.Kind.REFUND -> "Возврат"
            kind == Parsers.Kind.CASH -> "Наличные"
            kind == Parsers.Kind.CARD_BILL -> "Погашение карты"
            else -> merchantCategory(ctx, t.merchant) ?: "Другое"
        }
    }
}

object Ingest {
    const val CH = "recorded"

    /** Parse one message (SMS or notification). Returns a human summary if something was recorded. */
    fun handle(ctx: Context, text: String, ts: Long, src: Int, sender: String, title: String = ""): String? {
        val db = Db.get(ctx)
        val hash = "$src|$sender|${Parsers.clean(title + text).hashCode()}|${ts / 60000}"
        val r = Parsers.parse(if (title.isNotEmpty() && src != Src.SMS) "$title\n$text" else text)
        when (r) {
            is Parsers.Result.B -> {
                db.setBalance(r.b.account.ifEmpty { "Счёт" }, r.b.amount, ts); return null
            }
            is Parsers.Result.T -> return store(ctx, r.p, ts, src, hash, if (src == Src.SMS) text else "[$sender] $title\n$text")
            null -> {
                val all = Parsers.clean("$title $text")
                if (src != Src.SMS && Parsers.isAd(all)) return null
                // Google Wallet only shows payments; other apps must clearly talk about a payment
                val p = if (src == Src.NOTIF_WALLET || (src != Src.SMS && Parsers.looksLikePayment(all))) Parsers.parseWallet(title, text) else null
                if (p != null) return store(ctx, p, ts, src, hash, "[$sender] $title\n$text")
                if (looksFinancial(text)) db.addUnparsed(Src.label(src), sender, (if (title.isNotEmpty()) "$title\n" else "") + text, ts)
                return null
            }
        }
    }

    private fun looksFinancial(s: String) =
        listOf("₪", "ש\"ח", "שח", "בסך", "חשבונך", "כרטיס").any { s.contains(it) }

    private fun store(ctx: Context, p: Parsers.Parsed, ts: Long, src: Int, hash: String, raw: String): String? {
        val t = Tx(
            ts = Parsers.resolveTs(p, ts), amount = p.amount, currency = p.currency, type = p.type,
            merchant = p.merchant, card = p.card, account = p.account, note = p.note, src = src, raw = raw,
            hash = hash, installments = p.installments
        )
        Dict.enrich(ctx, t, p.kind)
        if (src != Src.SMS) {
            // apps sometimes post the same payment twice within a minute or two
            val dup = Db.get(ctx).query("(src & ?)!=0 AND ts BETWEEN ? AND ?", arrayOf(src.toString(), (t.ts - 300000).toString(), (t.ts + 300000).toString()))
                .any { abs(it.amount - t.amount) < 0.005 && Parsers.sameMerchant(it.merchant, t.merchant) }
            if (dup) return null
        }
        val (saved, isNew) = Db.get(ctx).addOrMerge(t)
        if (!isNew) return null
        if (saved.type == Types.INCOME && saved.account.isNotEmpty()) Transfers.link(ctx)
        if (p.installments != 0 && saved.type == Types.EXPENSE) {
            val plan = Plans.fromPurchase(ctx, saved, maxOf(0, p.installments))
            return "Рассрочка ${fmtMoney(saved.amount)} · ${plan.name}" + if (plan.count == 0) " — укажи число платежей" else ""
        }
        return "${fmtMoney(saved.amount, saved.currency)} · ${saved.merchantRu.ifEmpty { saved.merchant }.ifEmpty { saved.category }}"
    }

    fun notifyRecorded(ctx: Context, msg: String) {
        if (!Prefs.get(ctx).getBoolean("notify", true)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Записанные операции", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify((System.currentTimeMillis() % 100000).toInt(), NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle("Записано").setContentText(msg)
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    /** Reads SMS already on the phone from the given date. Returns number of new operations. */
    fun importSmsHistory(ctx: Context, from: Long): Int {
        var n = 0
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"),
            "date>=?", arrayOf(from.toString()), "date ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(1) ?: continue
                if (!(body.contains("בסך") || body.contains("יתרת"))) continue
                if (handle(ctx, body, c.getLong(2), Src.SMS, c.getString(0) ?: "") != null) n++
            }
        }
        Backup.schedule(ctx)
        return n
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        thread {
            try {
                msgs.groupBy { it.originatingAddress ?: "" }.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody ?: "" }
                    val ts = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                    Ingest.handle(ctx, body, ts, Src.SMS, sender)?.let { Ingest.notifyRecorded(ctx, it) }
                }
                Backup.schedule(ctx)
            } finally { pending.finish() }
        }
    }
}

class NotifService : NotificationListenerService() {
    companion object {
        const val WALLET = "com.google.android.apps.walletnfcrel"
        val CAL = setOf("com.onoapps.cal4u", "il.co.cal.app", "com.cal.calapp")
        val BANKS = setOf("com.ideomobile.hapoalim", "com.poalim.digital", "com.leumi.leumiwallet", "com.ideomobile.discount", "com.mizrahitefahot.nh")
        val SMS_APPS = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging", "com.miui.smsextra")
        /** Never payments: chats, AI assistants, mail, browsers, shops with promo pushes. */
        val NEVER = setOf(
            "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.plus", "com.viber.voip", "com.facebook.orca", "com.facebook.katana",
            "com.instagram.android", "com.google.android.gm", "com.microsoft.office.outlook", "com.android.chrome",
            "com.alibaba.aliexpresshd", "com.amazon.mShop.android.shopping", "com.einnovation.temu", "com.zzkko",
            "com.google.android.youtube", "com.miui.securitycenter", "com.android.vending"
        )
        fun blocked(pkg: String) = pkg in NEVER || pkg.startsWith("com.google.android.apps.messaging")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in SMS_APPS || blocked(pkg)) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence("android.title")?.toString() ?: ""
        val text = (ex.getCharSequence("android.bigText") ?: ex.getCharSequence("android.text"))?.toString() ?: ""
        if (text.isBlank()) return
        val extra = Prefs.get(this).getStringSet("pkgs", emptySet()) ?: emptySet()
        val src = when {
            pkg == WALLET -> Src.NOTIF_WALLET
            pkg in CAL -> Src.NOTIF_CAL
            pkg in BANKS || pkg in extra -> Src.NOTIF_OTHER
            else -> {
                // Remember apps that show money so the user can switch them on in Settings
                if (Regex("₪|ש\"ח|\\bILS\\b").containsMatchIn(title + text)) Prefs.rememberPkg(this, pkg, "$title: $text")
                return
            }
        }
        thread {
            Ingest.handle(this, text, sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime, src, pkg, title)?.let { Ingest.notifyRecorded(this, it) }
            Backup.schedule(this)
        }
    }
}

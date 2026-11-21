package com.shakhov.finance

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.math.abs

data class ImportRow(
    var ts: Long,
    var amount: Double,
    var type: String,
    var merchant: String,
    var note: String,
    var include: Boolean = true,
    var reason: String = "",
    var card: String = "",
    var installments: Int = 0
)

object Importers {

    fun load(ctx: Context, uri: Uri, name: String): List<ImportRow> {
        val lower = name.lowercase()
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val isPdf = bytes.size > 4 && String(bytes, 0, 4) == "%PDF"
        val rows = when {
            isPdf || lower.endsWith(".pdf") -> pdf(ctx, bytes)
            isZip || lower.endsWith(".xlsx") -> table(xlsx(bytes.inputStream()))
            else -> table(csv(String(bytes, Charsets.UTF_8)))
        }
        markKnown(ctx, rows)
        return rows
    }

    // ---------------- XLSX (minimal reader: shared strings + all sheets) ----------------
    fun xlsx(input: InputStream): List<List<String>> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.startsWith("xl/sharedStrings") || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { b ->
            val p = Xml.newPullParser(); p.setInput(b.inputStream(), "UTF-8")
            var inSi = false; val sb = StringBuilder(); var inT = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) { "si" -> { inSi = true; sb.clear() }; "t" -> inT = true }
                    XmlPullParser.TEXT -> if (inSi && inT) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) { "si" -> { shared += sb.toString(); inSi = false }; "t" -> inT = false }
                }
            }
        }
        val out = mutableListOf<List<String>>()
        files.keys.filter { it.startsWith("xl/worksheets/sheet") }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.forEach { k ->
            val p = Xml.newPullParser(); p.setInput(files[k]!!.inputStream(), "UTF-8")
            var row = sortedMapOf<Int, String>(); var col = 0; var type = ""; var inV = false; val sb = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> row = sortedMapOf()
                        "c" -> { col = colIndex(p.getAttributeValue(null, "r") ?: ""); type = p.getAttributeValue(null, "t") ?: ""; sb.clear() }
                        "v", "t" -> inV = true
                    }
                    XmlPullParser.TEXT -> if (inV) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v", "t" -> inV = false
                        "c" -> {
                            val v = sb.toString()
                            row[col] = if (type == "s") shared.getOrElse(v.toIntOrNull() ?: -1) { "" } else v
                        }
                        "row" -> if (row.isNotEmpty()) {
                            val max = row.lastKey()
                            out += (0..max).map { row[it] ?: "" }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return maxOf(0, n - 1)
    }

    fun csv(s: String): List<List<String>> {
        val sep = if (s.lineSequence().take(5).sumOf { it.count { c -> c == ';' } } > s.lineSequence().take(5).sumOf { it.count { c -> c == ',' } }) ';' else ','
        return s.lines().filter { it.isNotBlank() }.map { line ->
            val cells = mutableListOf<String>(); val sb = StringBuilder(); var q = false
            for (ch in line) {
                when {
                    ch == '"' -> q = !q
                    ch == sep && !q -> { cells += sb.toString().trim(); sb.clear() }
                    else -> sb.append(ch)
                }
            }
            cells += sb.toString().trim(); cells
        }
    }

    // ---------------- Generic table → rows (Cal export and similar) ----------------
    private val reInst = Regex("תשלום\\s*(\\d+)\\s*מתוך\\s*(\\d+)")

    fun table(rows: List<List<String>>): List<ImportRow> {
        val out = mutableListOf<ImportRow>()
        var dateC = -1; var merchC = -1; var amtC = -1; var noteC = -1; var cardC = -1
        var card = ""
        for (r in rows) {
            val joined = r.joinToString(" ")
            Regex("(?:המסתיים ב|מסתיים ב|ending|\\*{2,})\\s*-?\\s*(\\d{4})").find(joined)?.let { card = it.groupValues[1] }
            val isHeader = r.any { it.contains("תאריך") } && r.any { it.contains("סכום") }
            if (isHeader) {
                fun find(vararg keys: String) = keys.firstNotNullOfOrNull { k -> r.indexOfFirst { it.contains(k) }.takeIf { it >= 0 } } ?: -1
                dateC = find("תאריך עסקה", "תאריך רכישה", "תאריך")
                merchC = find("שם בית", "בית עסק", "שם העסק", "תיאור", "פירוט")
                amtC = find("סכום חיוב", "סכום לחיוב", "סכום בש\"ח", "סכום")
                noteC = find("הערות", "פירוט נוסף", "סוג עסקה", "סוג")
                cardC = find("4 ספרות", "כרטיס")
                continue
            }
            if (dateC < 0 || amtC < 0) continue
            val ts = parseDate(r.getOrNull(dateC) ?: "") ?: continue
            val amt = parseAmount(r.getOrNull(amtC) ?: "") ?: continue
            if (amt == 0.0) continue
            val note = (if (noteC >= 0) r.getOrNull(noteC) ?: "" else "").trim()
            val inst = reInst.find(note)
            val merchant = (if (merchC >= 0) r.getOrNull(merchC) ?: "" else "").trim()
            out += ImportRow(
                ts = ts, amount = abs(amt), type = if (amt < 0) Types.INCOME else Types.EXPENSE,
                merchant = merchant,
                note = if (inst != null) "рассрочка: платёж ${inst.groupValues[1]} из ${inst.groupValues[2]}" else note,
                card = (if (cardC >= 0) r.getOrNull(cardC)?.filter(Char::isDigit)?.takeLast(4) else null)?.takeIf { it.length == 4 } ?: card,
                installments = inst?.groupValues?.get(2)?.toIntOrNull() ?: 0
            )
        }
        return out
    }

    fun parseAmount(s: String): Double? {
        val t = s.replace("₪", "").replace("ש\"ח", "").replace(" ", "").replace("‏", "").trim()
        if (t.isEmpty()) return null
        val neg = t.startsWith("-") || t.endsWith("-") || (t.startsWith("(") && t.endsWith(")"))
        val n = t.trim('-', '(', ')').replace(",", "").toDoubleOrNull() ?: return null
        return if (neg) -n else n
    }

    fun parseDate(s: String): Long? {
        val t = s.trim()
        t.toDoubleOrNull()?.let { serial ->
            if (serial in 30000.0..80000.0) {
                val c = Calendar.getInstance(); c.set(1899, 11, 30, 12, 0, 0); c.set(Calendar.MILLISECOND, 0)
                c.add(Calendar.DAY_OF_MONTH, serial.toInt()); return c.timeInMillis
            }
            return null
        }
        val m = Regex("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})").find(t) ?: return null
        var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
        val c = Calendar.getInstance(); c.set(y, m.groupValues[2].toInt() - 1, m.groupValues[1].toInt(), 12, 0, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------------- Bank PDF (text-based; uses running balance to decide debit/credit) ----------------
    private val reD = Regex("(\\d{1,2}/\\d{1,2}/\\d{2,4})")
    private val reA = Regex("-?\\d{1,3}(?:,\\d{3})*\\.\\d{2}-?")
    private val reversedHints = listOf("תרוכשמ", "יאכ", "העברה".reversed(), "קיש", "בויח", "יוכיז")

    fun pdf(ctx: Context, bytes: ByteArray): List<ImportRow> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val text = PDDocument.load(bytes).use { d -> PDFTextStripper().apply { sortByPosition = true }.getText(d) }
        return pdfText(text)
    }

    fun pdfText(text: String): List<ImportRow> {
        data class L(val ts: Long, val nums: List<Double>, val desc: String)
        val lines = text.lines().mapNotNull { raw ->
            val line = Parsers.clean(raw)
            val d = reD.find(line) ?: return@mapNotNull null
            val nums = reA.findAll(line).map { m ->
                val v = m.value; val neg = v.startsWith("-") || v.endsWith("-")
                v.trim('-').replace(",", "").toDouble() * if (neg) -1 else 1
            }.toList()
            if (nums.isEmpty()) return@mapNotNull null
            var desc = line
            reD.findAll(line).forEach { desc = desc.replace(it.value, " ") }
            reA.findAll(line).forEach { desc = desc.replace(it.value, " ") }
            desc = desc.replace(Regex("\\b\\d{3,}\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (reversedHints.any { desc.contains(it) }) desc = desc.split(" ").reversed().joinToString(" ") { it.reversed() }
            L(Importers.parseDate(d.value) ?: return@mapNotNull null, nums, desc)
        }
        val out = mutableListOf<ImportRow>()
        lines.forEachIndexed { i, l ->
            val amt = abs(l.nums.first())
            val bal = if (l.nums.size >= 2) l.nums.last() else null
            var type: String? = null
            if (bal != null) {
                val prev = lines.getOrNull(i - 1)?.takeIf { it.nums.size >= 2 }?.nums?.last()
                val next = lines.getOrNull(i + 1)?.takeIf { it.nums.size >= 2 }?.nums?.last()
                type = when {
                    prev != null && abs(bal - prev - amt) < 0.01 -> Types.INCOME
                    prev != null && abs(prev - bal - amt) < 0.01 -> Types.EXPENSE
                    next != null && abs(bal - next - amt) < 0.01 -> Types.INCOME
                    next != null && abs(next - bal - amt) < 0.01 -> Types.EXPENSE
                    else -> null
                }
            }
            if (type == null) type = if (listOf("משכורת", "זיכוי", "העברה מ", "הפקדה").any { l.desc.contains(it) }) Types.INCOME else Types.EXPENSE
            if (type == Types.EXPENSE && listOf("כאל", "ויזה", "ישראכרט", "מקס", "כרטיסי אשראי").any { l.desc.contains(it) }) type = Types.TRANSFER
            out += ImportRow(l.ts, amt, type, l.desc, "")
        }
        return out
    }

    /** Untick lines already known, and installment payments whose full purchase is already recorded. */
    private fun markKnown(ctx: Context, rows: List<ImportRow>) {
        val db = Db.get(ctx)
        rows.forEach { r ->
            if (r.installments > 0) {
                val key = Parsers.merchantKey(r.merchant)
                val parent = db.query("installments!=0 AND ts<?", arrayOf(r.ts.toString())).any { Parsers.merchantKey(it.merchant) == key }
                if (parent) { r.include = false; r.reason = "платёж рассрочки — покупка уже записана целиком" }
            }
            val near = db.query("ts BETWEEN ? AND ? AND type=?", arrayOf((r.ts - 3 * Db.DAY).toString(), (r.ts + 3 * Db.DAY).toString(), r.type))
            val same = near.firstOrNull { abs(it.amount - r.amount) < 0.005 && it.src and Src.STATEMENT != 0 }
            if (same != null) { r.include = false; r.reason = "уже есть из выписки" }
            else if (near.any { abs(it.amount - r.amount) < 0.005 }) r.reason = "склеится с уже записанной"
        }
    }

    fun commit(ctx: Context, rows: List<ImportRow>): Int {
        var n = 0
        rows.filter { it.include }.forEach { r ->
            val t = Tx(ts = r.ts, amount = r.amount, type = r.type, merchant = r.merchant, note = r.note,
                card = r.card, src = Src.STATEMENT, installments = 0)
            Dict.enrich(ctx, t, if (r.type == Types.TRANSFER) Parsers.Kind.CARD_BILL else if (r.type == Types.INCOME) Parsers.Kind.CREDIT else null)
            if (r.type == Types.INCOME && r.merchant.contains("משכורת")) t.category = "Зарплата"
            if (Db.get(ctx).addOrMerge(t).second) n++
        }
        Backup.schedule(ctx)
        return n
    }
}

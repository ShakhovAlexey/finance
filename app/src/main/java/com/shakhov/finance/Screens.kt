package com.shakhov.finance

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs

fun parseNum(s: String): Double? = s.replace(',', '.').replace(" ", "").toDoubleOrNull()
fun numStr(v: Double): String = if (v == 0.0) "" else String.format(Locale.US, "%.2f", v).removeSuffix(".00")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(type: String, value: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Cats.forType(type).forEach { c ->
            FilterChip(selected = c == value, onClick = { onPick(c) }, label = { Text(c, fontSize = 13.sp) })
        }
    }
}

// ============================ Edit transaction ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDialog(orig: Tx, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val isNew = orig.id == 0L
    var amount by remember { mutableStateOf(numStr(orig.amount)) }
    var type by remember { mutableStateOf(orig.type) }
    var cat by remember { mutableStateOf(orig.category) }
    var merchant by remember { mutableStateOf(orig.merchant) }
    var ru by remember { mutableStateOf(orig.merchantRu) }
    var note by remember { mutableStateOf(orig.note) }
    var ts by remember { mutableLongStateOf(orig.ts) }
    var remember_ by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isNew) "Новая операция" else "Операция", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (orig.type == Types.PLAN || orig.grp.startsWith("plan:")) Text(
                    if (orig.type == Types.PLAN) "Покупка в рассрочку: в расходы идут ежемесячные платежи. Число платежей и сумму меняй во вкладке «Рассрочки»."
                    else "Платёж по рассрочке. График — во вкладке «Рассрочки».",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                if (orig.type != Types.PLAN) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(Types.EXPENSE to "Расход", Types.INCOME to "Доход", Types.TRANSFER to "Перевод").forEachIndexed { i, (t, l) ->
                        SegmentedButton(selected = type == t, onClick = {
                            type = t; if (cat !in Cats.forType(t)) cat = Cats.forType(t).first()
                        }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ${orig.currency}") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { showDate = true }) { Text(fmtDate(ts, "d MMMM yyyy, HH:mm")) }
                CategoryPicker(type, cat) { cat = it }
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Магазин / получатель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ru, { ru = it }, label = { Text("По-русски") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
                if (merchant.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember_, { remember_ = it }); Text("Запомнить категорию и название для этого магазина", fontSize = 13.sp)
                }
                if (orig.items.isNotEmpty()) {
                    Text("Чек:", fontWeight = FontWeight.SemiBold)
                    orig.items.forEach { Row { Text(it.nameRu.ifEmpty { it.name }, Modifier.weight(1f), fontSize = 13.sp); Text(fmtMoney(it.price), fontSize = 13.sp) } }
                }
                if (!isNew) Text("Источник: ${Src.label(orig.src)}${if (orig.card.isNotEmpty()) " · карта ${orig.card}" else ""}${if (orig.account.isNotEmpty()) " · счёт ${orig.account}" else ""}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                if (orig.raw.isNotEmpty()) {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Скрыть исходный текст" else "Исходный текст") }
                    if (showRaw) Text(orig.raw, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить", tint = Red) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Отмена") }
                    Button(onClick = {
                        val a = parseNum(amount)
                        if (a == null || a <= 0) { Toast.makeText(ctx, "Введи сумму", Toast.LENGTH_SHORT).show(); return@Button }
                        val t = orig.copy(amount = a, type = type, category = cat, merchant = merchant.trim(), merchantRu = ru.trim(), note = note.trim(), ts = ts)
                        if (isNew) db.addOrMerge(t) else { db.update(t); db.changed() }
                        if (remember_ && merchant.isNotBlank()) {
                            val key = Parsers.merchantKey(merchant)
                            db.setRule(key, ru.trim(), cat)
                            // apply to other operations of the same merchant that still have default guesses
                            db.query("merchant!=''").filter { Parsers.merchantKey(it.merchant) == key && it.id != t.id && it.grp.isEmpty() }.forEach {
                                var ch = false
                                if (it.category == "Другое" && it.type == type) { it.category = cat; ch = true }
                                if (ru.isNotBlank() && (it.merchantRu.isEmpty() || it.merchantRu == Parsers.translit(it.merchant))) { it.merchantRu = ru.trim(); ch = true }
                                if (ch) db.update(it)
                            }
                            db.changed()
                        }
                        Backup.schedule(ctx); onClose()
                    }) { Text("Сохранить") }
                }
            }
        }
    }
    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = ts)
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let { sel ->
                    val old = Calendar.getInstance().apply { timeInMillis = ts }
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                    ts = Calendar.getInstance().apply {
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), old.get(Calendar.HOUR_OF_DAY), old.get(Calendar.MINUTE))
                    }.timeInMillis
                }
                showDate = false
            }) { Text("OK") }
        }) { DatePicker(st) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить операцию?") },
        text = { Text("${fmtMoney(orig.amount, orig.currency)} · ${orig.merchantRu.ifEmpty { orig.merchant }}") },
        confirmButton = { TextButton(onClick = { db.delete(orig.id); db.changed(); Backup.schedule(ctx); confirmDelete = false; onClose() }) { Text("Удалить", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
    )
}

// ============================ Statement preview ============================

@Composable
fun PreviewScreen(rows0: List<ImportRow>, title: String, done: () -> Unit, report: (Importers.Recon) -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf(*rows0.toTypedArray()) }
    var ver by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Найдено строк: ${rows.size}. Сними галочки с лишних. Нажми на тип, чтобы поменять расход/доход/перевод.", fontSize = 13.sp)
            key(ver) {
                Text("Расходы: ${fmtMoney(rows.filter { it.include && it.type == Types.EXPENSE }.sumOf { it.amount })} · Доходы: ${fmtMoney(rows.filter { it.include && it.type == Types.INCOME }.sumOf { it.amount })}", fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(rows) { i, r ->
                key(ver) {
                    Row(Modifier.fillMaxWidth().padding(8.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(r.include, { rows[i] = r.copy(include = it); ver++ })
                        Column(Modifier.weight(1f)) {
                            Text(r.merchant.ifEmpty { "—" }, maxLines = 1, fontSize = 14.sp)
                            Text(fmtDate(r.ts, "d.MM.yy") + (if (r.note.isNotEmpty()) " · ${r.note}" else "") + (if (r.reason.isNotEmpty()) " · ${r.reason}" else ""),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2)
                        }
                        val tl = when (r.type) { Types.INCOME -> "доход"; Types.TRANSFER -> "перевод"; else -> "расход" }
                        Text(tl, Modifier.clickable {
                            val next = when (r.type) { Types.EXPENSE -> Types.INCOME; Types.INCOME -> Types.TRANSFER; else -> Types.EXPENSE }
                            rows[i] = r.copy(type = next); ver++
                        }.padding(6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(fmtMoney(r.amount), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = done, Modifier.weight(1f)) { Text("Отмена") }
            Button(onClick = {
                report(Importers.commitWithReport(ctx, rows.toList()))
            }, Modifier.weight(1f)) { Text("Импорт (${rows.count { it.include }})") }
        }
    }
}

// ============================ Receipt ============================

data class RItem(val name: String, val ru: String, val price: String, val cat: String)

@Composable
fun ReceiptScreen(uri: Uri, done: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var status by remember { mutableStateOf("Распознаю чек (иврит)… до минуты") }
    val items = remember { mutableStateListOf<RItem>() }
    var total by remember { mutableStateOf("") }
    var rawText by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var linkId by remember { mutableLongStateOf(-1L) }
    var merchant by remember { mutableStateOf("") }
    var editIdx by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        val r = withContext(Dispatchers.IO) { runCatching { Ocr.recognize(ctx, uri) } }
        r.onSuccess { text ->
            rawText = text
            val rec = Parsers.parseReceipt(text)
            rec.items.forEach { items += RItem(it.name, Dict.translate(ctx, it.name), numStr(it.price), Dict.itemCategory(ctx, it.name)) }
            total = rec.total?.let { numStr(it) } ?: numStr(rec.items.sumOf { it.price })
            merchant = text.lines().map { Parsers.clean(it) }.firstOrNull { l -> l.length >= 3 && l.count { it.isLetter() } >= 3 } ?: ""
            status = if (items.isEmpty()) "Позиции не распознались. Добавь вручную или переснимите ровнее, при хорошем свете." else ""
        }.onFailure { status = "Ошибка распознавания: ${it.message}" }
    }

    val paid = parseNum(total) ?: 0.0
    val sum = items.sumOf { parseNum(it.price) ?: 0.0 }
    val cands = remember(paid) {
        if (paid <= 0) emptyList() else db.query("ts>? AND type=?", arrayOf((System.currentTimeMillis() - 7 * Db.DAY).toString(), Types.EXPENSE))
            .filter { abs(it.amount - paid) < 0.01 || (it.grpTotal > 0 && abs(it.grpTotal - paid) < 0.01) }
    }
    LaunchedEffect(cands) { if (linkId == -1L) cands.firstOrNull()?.let { linkId = it.id; if (it.merchant.isNotEmpty()) merchant = it.merchant } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("Чек", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp))
            if (status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                if (status.startsWith("Распознаю")) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                Text(status, fontSize = 14.sp)
            }
            OutlinedTextField(merchant, { merchant = it }, label = { Text("Магазин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(total, { total = it }, label = { Text("Оплачено по чеку, ₪") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text("Сумма позиций: ${fmtMoney(sum)}" + if (abs(sum - paid) > 0.01 && paid > 0) " (разница ${fmtMoney(paid - sum)} — скидки/залог, уйдёт в самую большую категорию)" else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        itemsIndexed(items) { i, it ->
            Row(Modifier.fillMaxWidth().clickable { editIdx = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.ru.ifEmpty { it.name }, fontSize = 14.sp)
                    Text("${it.name} · ${it.cat}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
                Text(fmtMoney(parseNum(it.price) ?: 0.0), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
        item {
            TextButton(onClick = { items += RItem("", "", "", "Продукты"); editIdx = items.lastIndex }) { Text("+ Добавить позицию") }
            if (rawText.isNotEmpty()) {
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Скрыть распознанный текст" else "Показать распознанный текст") }
                if (showRaw) Text(rawText, fontSize = 11.sp)
            }
            Text("К какой оплате относится чек?", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            cands.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { linkId = c.id }) {
                    RadioButton(linkId == c.id, { linkId = c.id })
                    Text("${fmtDate(c.ts, "d.MM HH:mm")} · ${c.merchantRu.ifEmpty { c.merchant }} · ${fmtMoney(c.grpTotal.takeIf { it > 0 } ?: c.amount)}", fontSize = 13.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { linkId = 0 }) {
                RadioButton(linkId == 0L || (linkId == -1L && cands.isEmpty()), { linkId = 0 })
                Text("Новая операция (оплата ещё не пришла — склеится сама)", fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = done, Modifier.weight(1f)) { Text("Отмена") }
                Button(enabled = items.isNotEmpty() && paid > 0, onClick = {
                    scope.launch {
                        saveReceipt(ctx, items.toList(), paid, merchant.trim(), if (linkId > 0) linkId else 0L)
                        Toast.makeText(ctx, "Чек сохранён", Toast.LENGTH_SHORT).show(); done()
                    }
                }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
            }
        }
    }

    if (editIdx >= 0 && editIdx < items.size) {
        val it0 = items[editIdx]
        var name by remember(editIdx) { mutableStateOf(it0.name) }
        var ru by remember(editIdx) { mutableStateOf(it0.ru) }
        var price by remember(editIdx) { mutableStateOf(it0.price) }
        var cat by remember(editIdx) { mutableStateOf(it0.cat) }
        AlertDialog(onDismissRequest = { editIdx = -1 }, title = { Text("Позиция") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("На чеке") }, singleLine = true)
                OutlinedTextField(ru, { ru = it }, label = { Text("По-русски") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Цена") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                CategoryPicker(Types.EXPENSE, cat) { cat = it }
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && ru.isNotBlank() && ru != it0.ru) db.setWord(name.trim(), ru.trim())
                items[editIdx] = RItem(name.trim(), ru.trim(), price, cat); editIdx = -1
            }) { Text("OK") }
        }, dismissButton = {
            TextButton(onClick = { items.removeAt(editIdx); editIdx = -1 }) { Text("Удалить", color = Red) }
        })
    }
}

/** Splits a receipt into one operation per category (Продукты / Алкоголь / Хозтовары / Гигиена …). */
suspend fun saveReceipt(ctx: android.content.Context, rItems: List<RItem>, paid: Double, merchant: String, linkId: Long) = withContext(Dispatchers.IO) {
    val db = Db.get(ctx)
    val link = if (linkId > 0) db.byId(linkId) else null
    val groups = rItems.filter { (parseNum(it.price) ?: 0.0) != 0.0 }
        .map { Item(it.name, it.ru, parseNum(it.price)!!, "", it.cat) }
        .groupBy { it.category }.toList().sortedByDescending { g -> g.second.sumOf { it.price } }
    if (groups.isEmpty()) return@withContext
    val itemsSum = groups.sumOf { g -> g.second.sumOf { it.price } }
    val diff = Math.round((paid - itemsSum) * 100) / 100.0
    val grp = UUID.randomUUID().toString()
    val base = link ?: Tx(ts = System.currentTimeMillis(), amount = paid, src = Src.RECEIPT, merchant = merchant)
    if (merchant.isNotEmpty() && base.merchant.isEmpty()) base.merchant = merchant
    if (base.merchantRu.isEmpty()) {
        val key = Parsers.merchantKey(base.merchant)
        base.merchantRu = db.rule(key)?.first?.takeIf { it.isNotEmpty() } ?: if (Parsers.hasHebrew(base.merchant)) Dict.translate(ctx, base.merchant) else ""
    }
    groups.forEachIndexed { i, (cat, its) ->
        var amt = its.sumOf { it.price }
        if (i == 0) amt += diff
        amt = Math.round(amt * 100) / 100.0
        val note = if (groups.size > 1) "из чека ${fmtMoney(paid)}" else base.note
        val t = base.copy(
            id = if (i == 0) base.id else 0, amount = amt, category = cat, items = its,
            src = base.src or Src.RECEIPT, note = if (i == 0 && link != null) listOf(base.note, note).filter { it.isNotEmpty() }.distinct().joinToString("; ") else note,
            grp = if (groups.size > 1) grp else "", grpTotal = if (groups.size > 1) paid else 0.0,
            hash = if (i == 0) base.hash else ""
        )
        if (t.id > 0) db.update(t) else db.insert(t)
    }
    db.changed(); Backup.schedule(ctx)
}

// ============================ Reconciliation ============================

@Composable
fun ReconScreen(r0: Importers.Recon, onEdit: (Tx) -> Unit, done: () -> Unit) {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    val db = remember { Db.get(ctx) }
    // drop rows the user deleted meanwhile
    val onlyApp = remember(v) { r0.onlyApp.mapNotNull { db.byId(it.id) }.filter { it.src and Src.STATEMENT == 0 } }
    val added = remember(v) { r0.added.mapNotNull { db.byId(it.id) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("Сверка с выпиской", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp))
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text("Строк в выписке", Modifier.weight(1f)); Text("${r0.rows}") }
                    Row { Text(if (r0.card.isNotEmpty()) "К списанию по карте ${r0.card}" else "Итого по выписке", Modifier.weight(1f)); Text(fmtMoney(r0.statementSum), fontWeight = FontWeight.Bold) }
                    r0.chargeDay?.let { Text("День расчёта Cal: $it-е — рассрочки пересчитаны под него", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
                    HorizontalDivider()
                    Row { Text("✓ Уже были в приложении", Modifier.weight(1f)); Text("${r0.matched}", color = Green) }
                    Row { Text("+ Добавлены из выписки", Modifier.weight(1f)); Text("${added.size}") }
                    Row { Text("? Есть в приложении, нет в выписке", Modifier.weight(1f)); Text("${onlyApp.size}", color = if (onlyApp.isEmpty()) Green else Red) }
                }
            }
            if (onlyApp.isEmpty()) Text("Всё сходится 👍", Modifier.padding(vertical = 8.dp), color = Green, fontWeight = FontWeight.SemiBold)
        }
        if (onlyApp.isNotEmpty()) {
            item {
                Text("Нет в выписке (${fmtMoney(onlyApp.sumOf { it.amount })})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Обычно это свежие покупки — банк ещё не провёл. Если покупку отменили — удали её.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            items(onlyApp, key = { "o" + it.id }) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TxRow(t) { onEdit(t) } }
                    IconButton(onClick = { db.delete(t.id); db.changed(); Backup.schedule(ctx) }) { Icon(Icons.Default.Delete, "Удалить", tint = Red) }
                }
            }
        }
        if (added.isNotEmpty()) {
            item {
                Text("Добавлены из выписки (${fmtMoney(added.sumOf { it.amount })})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text("О них не было СМС или уведомления.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            items(added, key = { "a" + it.id }) { t -> TxRow(t) { onEdit(t) } }
        }
        item {
            Button(onClick = done, Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Готово") }
        }
    }
}

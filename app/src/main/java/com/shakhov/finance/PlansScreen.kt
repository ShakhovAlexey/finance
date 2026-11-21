package com.shakhov.finance

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.*

private val piePalette = listOf(
    Color(0xFF1E88E5), Color(0xFFFB8C00), Color(0xFF43A047), Color(0xFF8E24AA), Color(0xFFE53935),
    Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB), Color(0xFFD81B60), Color(0xFF7CB342)
)

@Composable
fun PieChart(parts: List<Pair<String, Double>>, centerTitle: String, centerValue: String) {
    val sum = parts.sumOf { it.second }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            val track = MaterialTheme.colorScheme.surfaceVariant
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val stroke = size.minDimension * 0.22f
                val d = size.minDimension - stroke
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                if (sum <= 0) drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
                var start = -90f
                parts.forEachIndexed { i, (_, v) ->
                    val sweep = (v / sum * 360).toFloat()
                    drawArc(piePalette[i % piePalette.size], start, sweep - if (parts.size > 1) 1.5f else 0f, false, tl, Size(d, d), style = Stroke(stroke))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerTitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text(centerValue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (parts.isEmpty()) Text("Нет данных", fontSize = 13.sp)
            parts.forEachIndexed { i, (name, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(piePalette[i % piePalette.size]))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(name, fontSize = 13.sp, maxLines = 1)
                        Text("${fmtMoney(v)} · ${if (sum > 0) Math.round(v * 100 / sum) else 0}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
fun PlansScreen() {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    val db = remember { Db.get(ctx) }
    val plans = remember(v) { Plans.all(db) }
    var mode by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Plan?>(null) }
    var dayDialog by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val month = monthStart(now)
    val active = plans.filter { it.count == 0 || Plans.remaining(it, now) > 0.005 || Plans.inMonth(it, month) > 0 }
    val monthParts = active.map { it.name to Plans.inMonth(it, month) }.filter { it.second > 0 }.sortedByDescending { it.second }
    val leftParts = active.map { it.name to Plans.remaining(it, now) }.filter { it.second > 0 }.sortedByDescending { it.second }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Рассрочки", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { dayDialog = true }) { Text("Расчёт Cal: ${Plans.chargeDay(ctx)}-е") }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp, 8.dp)) {
                SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Платёж в этом месяце") }
                SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Осталось выплатить") }
            }
        }
        item {
            if (mode == 0) PieChart(monthParts, fmtDate(now, "LLLL").replaceFirstChar { it.uppercase() }, fmtMoney(monthParts.sumOf { it.second }))
            else PieChart(leftParts, "Осталось", fmtMoney(leftParts.sumOf { it.second }))
        }
        if (plans.isEmpty()) item { Text("Рассрочек пока нет. Покупка «בתשלומים» по Cal появится здесь сама, а страховку и прочее можно добавить вручную.", Modifier.padding(16.dp), fontSize = 14.sp) }
        items(plans, key = { it.id }) { p -> PlanCard(p, now) { editing = p } }
        item {
            Button(onClick = {
                editing = Plan(name = "", source = Plans.BANK, firstTs = dayStart(now) + 9 * 3600000L, category = "Транспорт и авто")
            }, Modifier.fillMaxWidth().padding(16.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить рассрочку вручную") }
        }
    }
    editing?.let { PlanDialog(it) { editing = null } }
    if (dayDialog) {
        var d by remember { mutableStateOf(Plans.chargeDay(ctx).toString()) }
        AlertDialog(onDismissRequest = { dayDialog = false }, title = { Text("День расчёта по карте Cal") },
            text = {
                Column {
                    Text("Какого числа Cal списывает деньги со счёта (видно в приложении Cal → «מועד חיוב»).", fontSize = 13.sp)
                    OutlinedTextField(d, { d = it.filter(Char::isDigit).take(2) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = d.toIntOrNull()
                    if (n == null || n !in 1..31) return@TextButton
                    Prefs.get(ctx).edit().putInt("calDay", n).apply(); Plans.onChargeDayChanged(ctx); dayDialog = false
                }) { Text("OK") }
            }, dismissButton = { TextButton(onClick = { dayDialog = false }) { Text("Отмена") } })
    }
}

@Composable
fun PlanCard(p: Plan, now: Long, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(p.name.ifEmpty { p.merchant }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (p.count > 0) "${fmtMoney(Plans.payment(p, minOf(p.count, 2)))}/мес" else "—", fontWeight = FontWeight.SemiBold)
            }
            if (p.count == 0) {
                Text("Сколько платежей? Нажми, чтобы указать", color = Red, fontSize = 13.sp)
                Text("Сумма покупки ${fmtMoney(p.total)} · ${fmtDate(p.purchaseTs, "d MMMM")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            } else {
                val paid = Plans.paidCount(p, now)
                val total = Plans.total(p)
                LinearProgressIndicator(progress = { if (total > 0) (Plans.paidSum(p, now) / total).toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("Оплачено $paid из ${p.count} · осталось ${fmtMoney(Plans.remaining(p, now))} из ${fmtMoney(total)}", fontSize = 12.sp)
                val next = (1..p.count).firstOrNull { Plans.paymentTs(p, it) > now }
                Text(
                    (if (next != null) "Следующий: ${fmtDate(Plans.paymentTs(p, next), "d MMMM")} · последний: ${fmtDate(Plans.paymentTs(p, p.count), "LLLL yyyy")}" else "Выплачено") +
                        " · " + (if (p.source == Plans.CARD) "карта Cal${if (p.card.isNotEmpty()) " ${p.card}" else ""}" else "счёт в банке"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDialog(orig: Plan, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = orig.id == 0L
    var name by remember { mutableStateOf(orig.name) }
    var byMonthly by remember { mutableStateOf(orig.monthly > 0 || (isNew && orig.source == Plans.BANK)) }
    var total by remember { mutableStateOf(numStr(orig.total)) }
    var monthly by remember { mutableStateOf(numStr(orig.monthly)) }
    var count by remember { mutableStateOf(if (orig.count > 0) orig.count.toString() else "") }
    var source by remember { mutableStateOf(orig.source) }
    var firstTs by remember { mutableLongStateOf(orig.firstTs) }
    var cat by remember { mutableStateOf(orig.category) }
    var note by remember { mutableStateOf(orig.note) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val fromPurchase = orig.purchaseTs > 0 && orig.source == Plans.CARD

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isNew) "Новая рассрочка" else "Рассрочка", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(name, { name = it }, label = { Text("Название (например, Страховка машины)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (fromPurchase) Text("Покупка ${fmtDate(orig.purchaseTs, "d MMMM yyyy")} по карте Cal на ${fmtMoney(orig.total)}. Платежи — в день расчёта Cal.", fontSize = 13.sp)
                else {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(source == Plans.BANK, { source = Plans.BANK }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Со счёта в банке") }
                        SegmentedButton(source == Plans.CARD, { source = Plans.CARD }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("С карты Cal") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(byMonthly, { byMonthly = it }); Text("Знаю платёж в месяц (а не общую сумму)", fontSize = 13.sp)
                    }
                }
                if (byMonthly && !fromPurchase) OutlinedTextField(monthly, { monthly = it }, label = { Text("Платёж в месяц, ₪") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                else if (!fromPurchase) OutlinedTextField(total, { total = it }, label = { Text("Общая сумма, ₪") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text("Число платежей") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (!fromPurchase) OutlinedButton(onClick = { showDate = true }) {
                    Text("Первый платёж: " + if (firstTs > 0) fmtDate(firstTs, "d MMMM yyyy") else "выбрать дату")
                }
                val c = count.toIntOrNull() ?: 0
                val preview = Plan(total = if (fromPurchase) orig.total else if (byMonthly) 0.0 else parseNum(total) ?: 0.0,
                    monthly = if (byMonthly && !fromPurchase) parseNum(monthly) ?: 0.0 else 0.0, count = c, firstTs = firstTs, name = "")
                if (c > 0 && Plans.total(preview) > 0) Text(
                    "Платёж: ${fmtMoney(Plans.payment(preview, minOf(2, c)))} в месяц" +
                        (if (Plans.payment(preview, 1) != Plans.payment(preview, minOf(2, c))) " (первый ${fmtMoney(Plans.payment(preview, 1))})" else "") +
                        " · всего ${fmtMoney(Plans.total(preview))}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary
                )
                Text("Категория", fontWeight = FontWeight.SemiBold)
                CategoryPicker(Types.EXPENSE, cat) { cat = it }
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить", tint = Red) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Отмена") }
                    Button(onClick = {
                        val p = orig.copy(name = name.trim().ifEmpty { orig.merchant }, count = c, category = cat, note = note.trim(), source = source)
                        if (!fromPurchase) {
                            p.monthly = if (byMonthly) parseNum(monthly) ?: 0.0 else 0.0
                            p.total = if (byMonthly) 0.0 else parseNum(total) ?: 0.0
                            p.firstTs = firstTs; p.purchaseTs = 0
                        }
                        when {
                            p.name.isEmpty() -> Toast.makeText(ctx, "Напиши название", Toast.LENGTH_SHORT).show()
                            c <= 0 -> Toast.makeText(ctx, "Укажи число платежей", Toast.LENGTH_SHORT).show()
                            Plans.total(p) <= 0 -> Toast.makeText(ctx, "Укажи сумму", Toast.LENGTH_SHORT).show()
                            p.firstTs == 0L && !fromPurchase -> Toast.makeText(ctx, "Выбери дату первого платежа", Toast.LENGTH_SHORT).show()
                            else -> { Plans.save(ctx, p); onClose() }
                        }
                    }) { Text("Сохранить") }
                }
            }
        }
    }
    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = if (firstTs > 0) firstTs else System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let { sel ->
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                    firstTs = Calendar.getInstance().apply {
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 9, 0, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
                showDate = false
            }) { Text("OK") }
        }) { DatePicker(st) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить рассрочку?") },
        text = { Text(if (fromPurchase) "Покупка снова будет считаться расходом целиком." else "Будущие платежи исчезнут из расходов.") },
        confirmButton = { TextButton(onClick = { Plans.delete(ctx, orig); confirmDelete = false; onClose() }) { Text("Удалить", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
    )
}

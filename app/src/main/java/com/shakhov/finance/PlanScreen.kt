package com.shakhov.finance

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlanTab() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 0.dp)) {
            SegmentedButton(tab == 0, { tab = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Бюджет") }
            SegmentedButton(tab == 1, { tab = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Рассрочки") }
        }
        if (tab == 0) BudgetScreen() else PlansScreen()
    }
}

@Composable
private fun Bar(share: Float, modifier: Modifier = Modifier) {
    val col = when { share >= 1f -> Red; share >= 0.8f -> Color(0xFFFB8C00); else -> Green }
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().background(col))
    }
}

@Composable
fun BudgetScreen() {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    val now = System.currentTimeMillis()
    val month = monthStart(now)
    // heavy part runs in the background; the screen never freezes
    val state by produceState<Result<BudgetData>?>(null, v) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { BudgetData.load(ctx) } }
    }
    val data = state?.getOrNull()
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (data == null) {
        val e = state!!.exceptionOrNull()
        CrashLog.save(ctx, e!!, "План")
        Column(Modifier.padding(16.dp)) {
            Text("Не получилось посчитать план 😕", fontWeight = FontWeight.Bold)
            Text("Ошибка сохранена в копию на Диске — напиши мне «посмотри ошибку».", fontSize = 13.sp)
            Text(e.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    val income = data.income
    val goals = data.goals.map { it.first }
    val limits = data.limits.associate { it.first to it.second }
    val rec = data.recurring
    val fc = data.forecast
    var goalEdit by remember { mutableStateOf<Budget.Goal?>(null) }
    var goalIsNew by remember { mutableStateOf(false) }
    var depositFor by remember { mutableStateOf<Budget.Goal?>(null) }
    var limitEdit by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        // ---- forecast ----
        item {
            Card(Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 4.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Прогноз до конца месяца", fontWeight = FontWeight.Bold)
                    Row { Text("Доход", Modifier.weight(1f)); Text(fmtMoney(fc.income) + if (fc.expectedSalary > 0) " *" else "", color = Green) }
                    Row { Text("Расход ≈", Modifier.weight(1f)); Text(fmtMoney(Math.round(fc.expense).toDouble()), color = Red) }
                    if (fc.reserved > 0) Row { Text("В копилки", Modifier.weight(1f)); Text(fmtMoney(fc.reserved), color = Color(0xFF1E88E5)) }
                    HorizontalDivider()
                    Row {
                        Text("Останется ≈", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text((if (fc.rest < 0) "−" else "") + fmtMoney(Math.round(kotlin.math.abs(fc.rest)).toDouble()),
                            fontWeight = FontWeight.Bold, color = if (fc.rest < 0) Red else Green)
                    }
                    Text("Считаю: уже потрачено + постоянные платежи, которые ещё спишутся (${fmtMoney(fc.expectedRecurring)}) + обычные траты в том же темпе до конца месяца." +
                        if (fc.expectedSalary > 0) " * зарплата ещё не пришла — взял прошлую." else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        // ---- savings goals ----
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 8.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Копилки", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { goalIsNew = true; goalEdit = Budget.Goal("Отпуск", "✈️", 0.0, 10.0, 0.0) }) { Text("+ Новая") }
            }
            if (goals.isEmpty()) Text("Например: каждый месяц откладывать 10% дохода на отпуск. Деньги в копилке не считаются свободными, а траты на отпуск списываются из неё.",
                Modifier.padding(16.dp, 0.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        items(goals, key = { "g" + it.name }) { g ->
            val plan = Budget.planned(g, income)
            val gd = data.goals.first { it.first.name == g.name }
            val saved = gd.second
            val bal = gd.third
            Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp).clickable { goalIsNew = false; goalEdit = g }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.icon, fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                        Text(g.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(fmtMoney(bal), fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                    }
                    if (g.target > 0) {
                        Bar((bal / g.target).toFloat())
                        Text("цель ${fmtMoney(g.target)} · собрано ${Math.round(bal * 100 / g.target)}%", fontSize = 12.sp)
                    }
                    Text("В этом месяце: отложено ${fmtMoney(saved)} из ${fmtMoney(plan)}" + if (g.pct > 0) " (${numStr(g.pct)}% дохода)" else "", fontSize = 13.sp)
                    Row {
                        if (plan - saved > 0.5) Button(onClick = { Budget.deposit(ctx, g, plan - saved) }) { Text("Отложить ${fmtMoney(plan - saved)}") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { depositFor = g }) { Text("Другая сумма") }
                    }
                }
            }
        }
        // ---- limits ----
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 8.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Лимиты на месяц", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { limitEdit = "" }) { Text("+ Лимит") }
            }
            if (limits.isEmpty()) Text("Например: Продукты — 2 500 ₪. На 80% и при превышении придёт предупреждение.",
                Modifier.padding(16.dp, 0.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        items(limits.entries.toList(), key = { "l" + it.key }) { (cat, lim) ->
            val sp = data.limits.first { it.first == cat }.third
            Column(Modifier.fillMaxWidth().clickable { limitEdit = cat }.padding(16.dp, 8.dp)) {
                Row {
                    Text("${Cats.icon(cat)}  $cat", Modifier.weight(1f))
                    Text("${fmtMoney(sp)} из ${fmtMoney(lim)}", fontWeight = FontWeight.SemiBold, color = if (sp > lim) Red else MaterialTheme.colorScheme.onSurface)
                }
                Bar((sp / lim).toFloat(), Modifier.padding(top = 4.dp))
                Text(if (sp <= lim) "осталось ${fmtMoney(lim - sp)}" else "перерасход ${fmtMoney(sp - lim)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        // ---- recurring ----
        item {
            Text("Постоянные платежи", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
            val wait = rec.filter { it.paidTs == null }
            Text(if (rec.isEmpty()) "Появятся, когда наберётся история за 2–3 месяца (одно и то же списание каждый месяц)."
                else "Ещё спишется в этом месяце: ${fmtMoney(wait.sumOf { it.amount })} · всего в месяц ${fmtMoney(rec.sumOf { it.amount })}",
                Modifier.padding(16.dp, 2.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        items(rec, key = { "r" + it.key }) { r ->
            Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Cats.icon(r.category), modifier = Modifier.width(32.dp), fontSize = 18.sp)
                Column(Modifier.weight(1f)) {
                    Text(r.name, maxLines = 1)
                    Text(if (r.paidTs != null) "✓ списано ${fmtDate(r.paidTs, "d.MM")}" else "ожидается ~${r.day}-го", fontSize = 12.sp,
                        color = if (r.paidTs != null) Green else MaterialTheme.colorScheme.outline)
                }
                Text(fmtMoney(r.amount), fontWeight = FontWeight.SemiBold)
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    goalEdit?.let { g -> GoalDialog(g, goalIsNew) { goalEdit = null } }
    depositFor?.let { g ->
        var amt by remember { mutableStateOf("") }
        var take by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { depositFor = null }, title = { Text("Копилка «${g.name}»") }, text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!take, { take = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Положить") }
                    SegmentedButton(take, { take = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Забрать") }
                }
                OutlinedTextField(amt, { amt = it }, label = { Text("Сумма, ₪") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (take) Text("«Забрать» — вернуть деньги из копилки в свободные. Если тратишь на отпуск — просто ставь покупке категорию «${g.name}».", fontSize = 12.sp)
            }
        }, confirmButton = {
            TextButton(onClick = {
                val a = parseNum(amt) ?: return@TextButton
                Budget.deposit(ctx, g, if (take) -a else a); depositFor = null
            }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { depositFor = null }) { Text("Отмена") } })
    }
    limitEdit?.let { cur ->
        var cat by remember(cur) { mutableStateOf(cur.ifEmpty { "Продукты" }) }
        var amt by remember(cur) { mutableStateOf(limits[cur]?.let { numStr(it) } ?: "") }
        AlertDialog(onDismissRequest = { limitEdit = null }, title = { Text("Лимит на месяц") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(amt, { amt = it }, label = { Text("Сумма в месяц, ₪") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (cur.isEmpty()) CategoryPicker(Types.EXPENSE, cat) { cat = it } else Text("${Cats.icon(cur)} $cur")
                if (cur.isNotEmpty()) TextButton(onClick = { Budget.setLimit(ctx, cur, null); limitEdit = null }) { Text("Убрать лимит", color = Red) }
            }
        }, confirmButton = {
            TextButton(onClick = { Budget.setLimit(ctx, cat, parseNum(amt)); limitEdit = null }) { Text("Сохранить") }
        }, dismissButton = { TextButton(onClick = { limitEdit = null }) { Text("Отмена") } })
    }
}

@Composable
fun GoalDialog(g: Budget.Goal, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(g.name) }
    var icon by remember { mutableStateOf(g.icon) }
    var target by remember { mutableStateOf(numStr(g.target)) }
    var byPct by remember { mutableStateOf(g.fixed <= 0) }
    var pct by remember { mutableStateOf(numStr(g.pct)) }
    var fixed by remember { mutableStateOf(numStr(g.fixed)) }
    var del by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (isNew) "Новая копилка" else "Копилка") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("На что копим") }, singleLine = true)
            Row { listOf("✈️", "🏖️", "🚗", "🎁", "🏠", "💻", "🛡️", "🎓").forEach { e -> TextButton(onClick = { icon = e }, contentPadding = PaddingValues(2.dp)) { Text(e, fontSize = if (icon == e) 22.sp else 16.sp) } } }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(byPct, { byPct = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("% дохода") }
                SegmentedButton(!byPct, { byPct = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Сумма") }
            }
            if (byPct) OutlinedTextField(pct, { pct = it }, label = { Text("Процент от дохода месяца") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            else OutlinedTextField(fixed, { fixed = it }, label = { Text("Каждый месяц, ₪") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(target, { target = it }, label = { Text("Цель, ₪ (можно пусто)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (!isNew) TextButton(onClick = { del = true }) { Text("Удалить копилку", color = Red) }
            if (del) Text("Отложенное вернётся в свободные деньги. Нажми «Удалить» ещё раз.", fontSize = 12.sp, color = Red)
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (del) { Budget.deleteGoal(ctx, g.name); onClose(); return@TextButton }
            val n = name.trim()
            if (n.isEmpty()) { Toast.makeText(ctx, "Напиши название", Toast.LENGTH_SHORT).show(); return@TextButton }
            val ng = Budget.Goal(n, icon, parseNum(target) ?: 0.0, if (byPct) parseNum(pct) ?: 0.0 else 0.0, if (byPct) 0.0 else parseNum(fixed) ?: 0.0)
            if (ng.pct <= 0 && ng.fixed <= 0) { Toast.makeText(ctx, "Укажи процент или сумму", Toast.LENGTH_SHORT).show(); return@TextButton }
            Budget.putGoal(ctx, ng, if (isNew) null else g.name); onClose()
        }) { Text(if (del) "Удалить" else "Сохранить") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } })
}

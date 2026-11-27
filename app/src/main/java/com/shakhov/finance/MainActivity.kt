package com.shakhov.finance

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Seed.run(this)
        Migrate.run(this)
        setContent { App() }
        lifecycleScope.launch { Updater.check(this@MainActivity) }
    }
}

val Green = Color(0xFF2E7D32)
val Red = Color(0xFFC62828)

/** Incremented on every DB change so screens reload. */
@Composable
fun rememberDbVersion(): Int {
    var v by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { v++ }
        Db.listeners += l
        onDispose { Db.listeners -= l }
    }
    return v
}

sealed class Screen {
    data object Feed : Screen()
    data object Report : Screen()
    data object Add : Screen()
    data object Plans : Screen()
    data object Setup : Screen()
    data object Unparsed : Screen()
    data class Preview(val rows: List<ImportRow>, val title: String) : Screen()
    data class Receipt(val uri: Uri) : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val scheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF81C784)) else lightColorScheme(primary = Color(0xFF2E7D32))
    MaterialTheme(colorScheme = scheme) {
        var screen by remember { mutableStateOf<Screen>(Screen.Feed) }
        var editing by remember { mutableStateOf<Tx?>(null) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple(Screen.Feed, "Лента", Icons.AutoMirrored.Filled.List),
                        Triple(Screen.Report, "Отчёт", Icons.Default.PieChart),
                        Triple(Screen.Plans, "Рассрочки", Icons.Default.CreditCard),
                        Triple(Screen.Add, "Добавить", Icons.Default.AddCircle),
                        Triple(Screen.Setup, "Настройки", Icons.Default.Settings)
                    ).forEach { (s, label, icon) ->
                        NavigationBarItem(selected = screen == s, onClick = { screen = s }, icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1, fontSize = 11.sp) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (val s = screen) {
                    Screen.Feed -> FeedScreen(onEdit = { editing = it }, onUnparsed = { screen = Screen.Unparsed })
                    Screen.Report -> ReportScreen(onEdit = { editing = it })
                    Screen.Plans -> PlansScreen()
                    Screen.Add -> AddScreen(go = { screen = it }, onManual = { editing = it })
                    Screen.Setup -> SetupScreen()
                    Screen.Unparsed -> UnparsedScreen(onBack = { screen = Screen.Feed }, onManual = { editing = it })
                    is Screen.Preview -> PreviewScreen(s.rows, s.title, done = { screen = Screen.Feed })
                    is Screen.Receipt -> ReceiptScreen(s.uri, done = { screen = Screen.Feed })
                }
            }
        }
        editing?.let { t -> EditDialog(t, onClose = { editing = null }) }
    }
}

// ============================ Feed ============================

@Composable
fun MonthBar(month: Long, onChange: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(addMonths(month, -1)) }) { Icon(Icons.Default.ChevronLeft, "Предыдущий месяц") }
        Text(fmtDate(month, "LLLL yyyy").replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(addMonths(month, 1)) }) { Icon(Icons.Default.ChevronRight, "Следующий месяц") }
    }
}

fun expenseTotal(l: List<Tx>) = l.filter { it.type == Types.EXPENSE && it.currency == "ILS" }.sumOf { it.amount }
fun incomeTotal(l: List<Tx>) = l.filter { it.type == Types.INCOME && it.currency == "ILS" }.sumOf { it.amount }

@Composable
fun FeedScreen(onEdit: (Tx) -> Unit, onUnparsed: () -> Unit) {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    var month by remember { mutableLongStateOf(monthStart(System.currentTimeMillis())) }
    val db = remember { Db.get(ctx) }
    val list = remember(v, month) { db.between(month, addMonths(month, 1)) }
    val balances = remember(v) { db.balances() }
    val unparsed = remember(v) { db.unparsed().size }
    LazyColumn(Modifier.fillMaxSize()) {
        item { MonthBar(month) { month = it } }
        item {
            Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row { Text("Расходы", Modifier.weight(1f)); Text(fmtMoney(expenseTotal(list)), color = Red, fontWeight = FontWeight.Bold) }
                    Row { Text("Доходы", Modifier.weight(1f)); Text(fmtMoney(incomeTotal(list)), color = Green, fontWeight = FontWeight.Bold) }
                    val inc = incomeTotal(list); val rest = inc - expenseTotal(list)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row {
                        Text("Остаток с дохода", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text((if (rest < 0) "−" else "") + fmtMoney(kotlin.math.abs(rest)), color = if (rest < 0) Red else Green, fontWeight = FontWeight.Bold)
                    }
                    if (inc > 0) Text("потрачено ${Math.round(expenseTotal(list) * 100 / inc)}% дохода", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    if (balances.isNotEmpty()) Spacer(Modifier.height(4.dp))
                    balances.forEach { (acc, amt, ts) ->
                        val jointMark = if (acc == Transfers.joint(ctx)) " (общий)" else ""
                        Row { Text("Счёт $acc$jointMark · ${fmtDate(ts, "d.MM")}", Modifier.weight(1f), fontSize = 13.sp); Text(fmtMoney(amt), fontSize = 13.sp) }
                    }
                }
            }
        }
        item { UpdateBanner() }
        if (unparsed > 0) item {
            Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp).clickable { onUnparsed() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text("Не распознано сообщений: $unparsed — посмотреть", Modifier.padding(12.dp))
            }
        }
        if (list.isEmpty()) item { Text("За этот месяц операций нет", Modifier.padding(24.dp)) }
        list.groupBy { dayStart(it.ts) }.forEach { (day, txs) ->
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 2.dp)) {
                    Text(fmtDate(day, "EEEE, d MMMM").replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(fmtMoney(expenseTotal(txs)), fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            items(txs, key = { it.id }) { t -> TxRow(t) { onEdit(t) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun catColor(c: String): Color {
    val palette = listOf(0xFF43A047, 0xFFFB8C00, 0xFF1E88E5, 0xFF8E24AA, 0xFF00897B, 0xFF6D4C41, 0xFFE53935, 0xFF3949AB,
        0xFFD81B60, 0xFF795548, 0xFF7CB342, 0xFF5E35B1, 0xFF00ACC1, 0xFFFDD835, 0xFF546E7A, 0xFF9E9E9E)
    val i = Cats.all().indexOf(c).let { if (it < 0) c.hashCode() and 0xff else it }
    return Color(palette[i % palette.size])
}

@Composable
fun TxRow(t: Tx, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(catColor(t.category)), contentAlignment = Alignment.Center) {
            Text(t.category.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.merchantRu.ifEmpty { t.merchant }.ifEmpty { t.category }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                add(t.category)
                if (t.merchantRu.isNotEmpty() && t.merchant.isNotEmpty() && t.merchant != t.merchantRu) add(t.merchant)
                if (t.items.isNotEmpty()) add("чек: ${t.items.size} поз.")
                if (t.grp.startsWith("plan:") && t.type == Types.EXPENSE && t.note.isNotEmpty()) add(t.note)
            }.joinToString(" · ")
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val (sign, col) = when (t.type) {
            Types.INCOME -> "+" to Green
            Types.TRANSFER -> "↔ " to MaterialTheme.colorScheme.outline
            Types.PLAN -> "" to MaterialTheme.colorScheme.outline
            else -> "−" to MaterialTheme.colorScheme.onSurface
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(sign + fmtMoney(t.amount, t.currency), color = col, fontWeight = FontWeight.SemiBold)
            if (t.type == Types.PLAN) Text("в рассрочку", fontSize = 11.sp, color = col)
        }
    }
}

// ============================ Report ============================

@Composable
fun ReportScreen(onEdit: (Tx) -> Unit) {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    var month by remember { mutableLongStateOf(monthStart(System.currentTimeMillis())) }
    val db = remember { Db.get(ctx) }
    val list = remember(v, month) { db.between(month, addMonths(month, 1)) }
    val prev = remember(v, month) { db.between(addMonths(month, -1), month) }
    var open by remember { mutableStateOf<String?>(null) }
    val exp = list.filter { it.type == Types.EXPENSE && it.currency == "ILS" }
    val total = exp.sumOf { it.amount }
    val byCat = exp.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    LazyColumn(Modifier.fillMaxSize()) {
        item { MonthBar(month) { month = it } }
        item {
            Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp)) {
                Column(Modifier.padding(14.dp)) {
                    val inc = incomeTotal(list)
                    Row { Text("Расходы", Modifier.weight(1f)); Text(fmtMoney(total), color = Red, fontWeight = FontWeight.Bold) }
                    Row { Text("Доходы", Modifier.weight(1f)); Text(fmtMoney(inc), color = Green, fontWeight = FontWeight.Bold) }
                    Row { Text("Остаток месяца", Modifier.weight(1f)); Text(fmtMoney(inc - total), fontWeight = FontWeight.Bold) }
                    val pe = expenseTotal(prev)
                    if (pe > 0) Text("Прошлый месяц: ${fmtMoney(pe)} (${if (total >= pe) "+" else "−"}${fmtMoney(kotlin.math.abs(total - pe))})",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    val days = ((minOf(System.currentTimeMillis(), addMonths(month, 1)) - month) / Db.DAY).coerceAtLeast(1)
                    Text("В среднем в день: ${fmtMoney(total / days)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        items(byCat, key = { it.key }) { (cat, sum) ->
            Column(Modifier.fillMaxWidth().clickable { open = if (open == cat) null else cat }.padding(16.dp, 8.dp)) {
                Row {
                    Text(cat, Modifier.weight(1f))
                    Text("${fmtMoney(sum)}  ${if (total > 0) (sum * 100 / total).toInt() else 0}%", fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((if (total > 0) sum / total else 0.0).toFloat()).fillMaxHeight().background(catColor(cat)))
                }
            }
            if (open == cat) exp.filter { it.category == cat }.forEach { t -> TxRow(t) { onEdit(t) } }
        }
        val incomes = list.filter { it.type == Types.INCOME }
        if (incomes.isNotEmpty()) {
            item { Text("Доходы", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold) }
            items(incomes, key = { "i" + it.id }) { t -> TxRow(t) { onEdit(t) } }
        }
        val foreign = list.filter { it.currency != "ILS" && it.type == Types.EXPENSE }
        if (foreign.isNotEmpty()) {
            item { Text("В валюте (не входят в сумму, придут в шекелях с выпиской)", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), fontSize = 13.sp) }
            items(foreign, key = { "f" + it.id }) { t -> TxRow(t) { onEdit(t) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ============================ Add ============================

fun fileName(ctx: android.content.Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"

@Composable
fun AddScreen(go: (Screen) -> Unit, onManual: (Tx) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photoUri?.let { go(Screen.Receipt(it)) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { go(Screen.Receipt(it)) } }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult
        busy = "Читаю файл…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Importers.load(ctx, u, fileName(ctx, u)) } }
            busy = null
            r.onSuccess { rows ->
                if (rows.isEmpty()) Toast.makeText(ctx, "Не нашёл операций в файле. Пришли образец — допилю под него.", Toast.LENGTH_LONG).show()
                else go(Screen.Preview(rows, fileName(ctx, u)))
            }.onFailure { Toast.makeText(ctx, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    var smsDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Добавить", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        BigButton(Icons.Default.Edit, "Вручную") { onManual(Tx(ts = System.currentTimeMillis(), amount = 0.0, src = Src.MANUAL)) }
        BigButton(Icons.Default.PhotoCamera, "Сфотографировать чек") {
            val f = File(ctx.cacheDir, "receipts").apply { mkdirs() }.let { File(it, "r${System.currentTimeMillis()}.jpg") }
            val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            photoUri = u; camera.launch(u)
        }
        BigButton(Icons.Default.Image, "Чек из галереи") { gallery.launch("image/*") }
        BigButton(Icons.Default.UploadFile, "Выписка: Cal (xlsx), банк (PDF), CSV") {
            file.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/*", "application/vnd.ms-excel", "*/*"))
        }
        BigButton(Icons.Default.Sms, "Загрузить старые СМС из телефона") { smsDialog = true }
        busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(it) } }
    }
    if (smsDialog) SmsHistoryDialog(onClose = { smsDialog = false })
}

@Composable
fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    ElevatedButton(onClick = onClick, Modifier.fillMaxWidth().height(56.dp)) {
        Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(text, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsHistoryDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = Prefs.get(ctx)
    val state = rememberDatePickerState(initialSelectedDateMillis = prefs.getLong("smsFrom", System.currentTimeMillis() - 30 * Db.DAY))
    var running by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(enabled = !running, onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    perm.launch(Manifest.permission.READ_SMS); return@TextButton
                }
                running = true
                val from = state.selectedDateMillis ?: return@TextButton
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Ingest.importSmsHistory(ctx, from) }
                    Toast.makeText(ctx, "Новых операций из СМС: $n", Toast.LENGTH_LONG).show()
                    onClose()
                }
            }) { Text(if (running) "Читаю…" else "Загрузить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } }
    ) {
        Text("С какой даты читать СМС? (старая таблица заканчивается 14 ноября)", Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
        DatePicker(state, showModeToggle = false, title = null, headline = null)
    }
}

// ============================ Unparsed ============================

@Composable
fun UnparsedScreen(onBack: () -> Unit, onManual: (Tx) -> Unit) {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    val db = remember { Db.get(ctx) }
    val list = remember(v) { db.unparsed() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                Text("Не распознано", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text("Похоже на деньги, но формат незнакомый. Можно записать вручную. Пришли такие тексты — добавлю их формат.",
                Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp), fontSize = 13.sp)
        }
        items(list, key = { it.id }) { u ->
            Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${u.source} · ${u.sender} · ${fmtDate(u.ts, "d.MM HH:mm")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(u.text, fontSize = 14.sp)
                    Row {
                        TextButton(onClick = {
                            onManual(Tx(ts = u.ts, amount = 0.0, src = Src.MANUAL, raw = u.text)); db.deleteUnparsed(u.id)
                        }) { Text("Записать вручную") }
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("sms", u.text))
                            Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                        }) { Text("Копировать") }
                        TextButton(onClick = { db.deleteUnparsed(u.id) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}

// ============================ Setup ============================

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val v = rememberDbVersion()
    var tick by remember { mutableIntStateOf(0) }
    val prefs = Prefs.get(ctx)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val smsOk = remember(tick) {
        listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
    val notifOk = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val battOk = remember(tick) { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val postPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("backupUri", u.toString()).apply()
        val ok = Backup.write(ctx); Backup.schedule(ctx); tick++
        Toast.makeText(ctx, if (ok) "Копия сохранена" else "Не удалось записать файл", Toast.LENGTH_LONG).show()
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching { Backup.restore(ctx, u) }.onSuccess { Toast.makeText(ctx, "Данные восстановлены", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(ctx, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(u)!!.use { o ->
                o.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                o.write("Дата;Тип;Категория;Сумма;Валюта;Магазин;По-русски;Карта;Заметка;Источник\n".toByteArray())
                Db.get(ctx).query(order = "ts").forEach { t ->
                    val cells = listOf(fmtDate(t.ts, "dd.MM.yyyy HH:mm"), t.type, t.category, String.format(java.util.Locale.US, "%.2f", t.amount), t.currency,
                        t.merchant, t.merchantRu, t.card, t.note, Src.label(t.src)).joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" }
                    o.write((cells + "\n").toByteArray())
                }
            }
        }.onSuccess { Toast.makeText(ctx, "Готово", Toast.LENGTH_SHORT).show() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Настройка", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        SetupItem("1. Чтение СМС", smsOk, "Разрешить") {
            smsPerm.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        }
        if (!smsOk || !notifOk) Text(
            "Если Xiaomi пишет «Ограниченная настройка» — открой «О приложении», нажми ⋮ вверху справа → «Разрешить ограниченные настройки», потом вернись сюда.",
            fontSize = 13.sp
        )
        if (!smsOk || !notifOk) OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Открыть «О приложении»") }
        SetupItem("2. Доступ к уведомлениям (Google Кошелёк, Cal, банк)", notifOk, "Открыть") {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        SetupItem("3. Без ограничений батареи", battOk, "Открыть") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        SetupItem("4. Автозапуск (Xiaomi)", null, "Открыть") {
            val i = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            runCatching { ctx.startActivity(i) }.onFailure {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        var notify by remember { mutableStateOf(prefs.getBoolean("notify", true)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Показывать «Записано: 70 ₪ …»", Modifier.weight(1f))
            Switch(notify, onCheckedChange = {
                notify = it; prefs.edit().putBoolean("notify", it).apply()
                if (it && Build.VERSION.SDK_INT >= 33) postPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        HorizontalDivider()
        Text("Общий счёт с бывшей женой (алименты)", fontWeight = FontWeight.Bold)
        Text("Что приходит на этот счёт — это твои алименты: считается расходом «Алименты», а не доходом. Перевод с основного счёта не задваивается.", fontSize = 13.sp)
        val accounts = remember(v) { Transfers.accounts(ctx) }
        var joint by remember { mutableStateOf(Transfers.joint(ctx)) }
        if (accounts.isEmpty()) Text("Счета появятся после первой СМС банка.", fontSize = 13.sp)
        (listOf("") + accounts).forEach { acc ->
            Row(Modifier.fillMaxWidth().clickable {
                joint = acc; prefs.edit().putString("jointAccount", acc).apply()
                // un-mark old alimony if the account was switched off
                if (acc.isEmpty()) Db.get(ctx).query("category=?", arrayOf(Transfers.ALIMONY)).filter { it.account.isNotEmpty() }
                    .forEach { it.type = Types.TRANSFER; it.category = "Перевод между счетами"; Db.get(ctx).update(it) }
                Transfers.link(ctx); Backup.schedule(ctx)
            }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(joint == acc, null)
                Text(if (acc.isEmpty()) "Нет общего счёта" else "Счёт $acc")
            }
        }
        HorizontalDivider()
        Text("Облачная копия", fontWeight = FontWeight.Bold)
        val bu = remember(tick, v) { Backup.uri(ctx) }
        val at = remember(tick, v) { prefs.getLong("backupAt", 0) }
        Text(if (bu == null) "Не настроена. Нажми «Выбрать место» → слева меню → Google Диск → сохрани файл."
            else "Файл: ${fileName(ctx, bu)}. Последняя копия: ${if (at > 0) fmtDate(at, "d MMMM HH:mm") else "—"}. Обновляется сама после изменений.", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickBackup.launch("finance-backup.json") }) { Text("Выбрать место") }
            if (bu != null) OutlinedButton(onClick = { Toast.makeText(ctx, if (Backup.write(ctx)) "Копия сохранена" else "Ошибка записи", Toast.LENGTH_SHORT).show(); tick++ }) { Text("Копия сейчас") }
        }
        OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "*/*")) }) { Text("Восстановить из копии") }
        OutlinedButton(onClick = { exportCsv.launch("finance.csv") }) { Text("Выгрузить всё в CSV") }
        HorizontalDivider()
        val seen = remember(tick) { Prefs.seenPkgs(ctx).filterKeys { !NotifService.blocked(it) } }
        if (seen.isNotEmpty()) {
            Text("Другие приложения с суммами в уведомлениях", fontWeight = FontWeight.Bold)
            Text("Включи, если это платёжное приложение (bit, Yellow, банк).", fontSize = 13.sp)
            var enabled by remember { mutableStateOf(prefs.getStringSet("pkgs", emptySet())!!.toSet()) }
            seen.forEach { (pkg, sample) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(pkg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(sample, fontSize = 12.sp, maxLines = 2) }
                    Switch(pkg in enabled, onCheckedChange = {
                        enabled = if (it) enabled + pkg else enabled - pkg
                        prefs.edit().putStringSet("pkgs", enabled).apply()
                    })
                }
            }
        }
        HorizontalDivider()
        val scope = rememberCoroutineScope()
        val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
        Text("Версия $ver · операций в базе: ${remember(v) { Db.get(ctx).count() }}", fontSize = 13.sp)
        OutlinedButton(onClick = {
            scope.launch {
                val info = Updater.check(ctx)
                if (info == null) Toast.makeText(ctx, "У тебя последняя версия", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Проверить обновления") }
        UpdateBanner()
    }
}

@Composable
fun SetupItem(title: String, ok: Boolean?, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (ok) { true -> Icons.Default.CheckCircle; false -> Icons.Default.Error; null -> Icons.Default.Info },
                null, tint = when (ok) { true -> Green; false -> Red; null -> MaterialTheme.colorScheme.outline }
            )
            Spacer(Modifier.width(10.dp))
            Text(title, Modifier.weight(1f))
            if (ok != true) TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    when (val x = st) {
        is Updater.State.Available -> Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Есть новая версия ${x.info.name}", Modifier.weight(1f))
                Button(onClick = {
                    if (!Updater.canInstall(ctx)) Updater.openInstallPermission(ctx)
                    else scope.launch { Updater.downloadAndInstall(ctx, x.info) }
                }) { Text(if (Updater.canInstall(ctx)) "Обновить" else "Разрешить установку") }
            }
        }
        is Updater.State.Downloading -> Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Скачиваю обновление… ${x.percent}%")
                LinearProgressIndicator(progress = { x.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        is Updater.State.Error -> Text(x.msg, Modifier.padding(16.dp, 4.dp), color = Red)
        Updater.State.Idle -> {}
    }
}

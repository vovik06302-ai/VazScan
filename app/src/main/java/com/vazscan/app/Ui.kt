@file:OptIn(ExperimentalMaterial3Api::class)

package com.vazscan.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val RED = Color(0xFFC62828)
private val ORANGE = Color(0xFFEF6C00)
private val BLUE = Color(0xFF1E88E5)
private val GREEN = Color(0xFF2E7D32)
private val GRAY = Color(0xFF757575)

fun sevColor(s: Int): Color = when (s) {
    3 -> RED
    2 -> ORANGE
    else -> BLUE
}

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(i, "Поделиться отчётом")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(chooser)
}

fun btPermission(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

@Composable
fun AppRoot(vm: MainViewModel) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Подключение", "Диагностика", "Ошибки", "Датчики", "История", "Терминал")
        Scaffold(
            topBar = { TopAppBar(title = { Text("VAZ Scan") }) }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                vm.error?.let { e ->
                    Card(Modifier.fillMaxWidth().padding(8.dp).clickable { vm.error = null }) {
                        Text(e, Modifier.padding(12.dp), color = RED)
                    }
                }
                when (tab) {
                    0 -> ConnectScreen(vm)
                    1 -> ScanScreen(vm)
                    2 -> ErrorsScreen(vm)
                    3 -> LiveScreen(vm)
                    4 -> HistoryScreen(vm)
                    else -> TerminalScreen(vm)
                }
            }
        }
    }
}

@Composable
fun SeverityDot(color: Color) {
    Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
fun StatusCard(vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityDot(
                    when {
                        vm.connected && vm.adapter?.ecuResponds == true -> GREEN
                        vm.connected -> ORANGE
                        else -> GRAY
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(vm.status, style = MaterialTheme.typography.titleMedium)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.demo) Text("ДЕМО-РЕЖИМ: данные имитируются", color = ORANGE, fontWeight = FontWeight.Bold)
            vm.adapter?.let { a ->
                Text("Адаптер: ${a.version}")
                Text("Протокол: ${a.protocol}")
                a.voltage?.let { Text("Напряжение бортсети: ${"%.1f".format(it)} В") }
                vm.vin?.let { Text("VIN: $it") }
                if (vm.supported.isNotEmpty()) Text("Поддерживаемых параметров: ${vm.supported.size}")
                a.notes.forEach { Text(it, color = ORANGE, fontSize = 13.sp) }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var hasPerm by remember { mutableStateOf(btPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    fun refresh() {
        devices = if (hasPerm) {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: emptyList()
        } else emptyList()
    }
    LaunchedEffect(hasPerm) { refresh() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { StatusCard(vm) }
        if (!vm.connected) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Как подключиться", style = MaterialTheme.typography.titleMedium)
                        Text("1. Вставьте адаптер ELM327 в разъём OBD-II автомобиля и включите зажигание.")
                        Text("2. В настройках телефона Bluetooth сопрягите адаптер (пароль 1234 или 0000).")
                        Text("3. Вернитесь сюда и выберите адаптер в списке ниже.")
                    }
                }
            }
            if (!hasPerm) {
                item {
                    Button(
                        onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Разрешить доступ к Bluetooth") }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сопряжённые устройства", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refresh() }) { Text("Обновить") }
                    }
                }
                if (devices.isEmpty()) {
                    item { Text("Нет сопряжённых устройств. Сначала сопрягите адаптер в настройках Bluetooth телефона.") }
                }
                items(devices, key = { it.address }) { d ->
                    val name = d.name ?: "Без имени"
                    val likely = name.uppercase().let { it.contains("OBD") || it.contains("ELM") || it.contains("LINK") }
                    Card(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.connectBt(d) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(name, fontWeight = FontWeight.Bold)
                            Text(d.address, fontSize = 12.sp, color = GRAY)
                            if (likely) Text("Похоже на OBD-адаптер", fontSize = 12.sp, color = GREEN)
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { vm.connectDemo() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Демо-режим (без автомобиля)")
                }
            }
        } else {
            item {
                OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Отключиться") }
            }
        }
    }
}

@Composable
fun DtcCard(code: String, kind: String) {
    val info = remember(code) { DtcDb.lookup(code) }
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityDot(sevColor(info.severity))
                Spacer(Modifier.width(8.dp))
                Text(code, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(kind, fontSize = 12.sp, color = GRAY)
            }
            Text(info.title)
            if (open) {
                if (info.causes.isNotEmpty()) {
                    Text("Возможные причины", fontWeight = FontWeight.Bold)
                    info.causes.forEachIndexed { i, c -> Text("${i + 1}. $c") }
                }
                if (info.steps.isNotEmpty()) {
                    Text("Что делать", fontWeight = FontWeight.Bold)
                    info.steps.forEach { Text("• $it") }
                }
            } else {
                Text("Нажмите, чтобы увидеть причины и рекомендации", fontSize = 12.sp, color = GRAY)
            }
        }
    }
}

@Composable
fun FindingCard(x: Finding) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityDot(sevColor(x.severity))
                Spacer(Modifier.width(8.dp))
                Text(x.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Text(x.details, fontSize = 14.sp)
            x.actions.forEach { Text("• $it", fontSize = 14.sp) }
        }
    }
}

fun LazyListScope.dtcSection(title: String, codes: List<String>, kind: String) {
    item { Text(title, style = MaterialTheme.typography.titleMedium) }
    if (codes.isEmpty()) {
        item { Text("Нет", color = GREEN) }
    } else {
        items(codes) { DtcCard(it, kind) }
    }
}

@Composable
fun ReadinessCard(r: Readiness) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (r.milOn) "Лампа Check Engine: ГОРИТ" else "Лампа Check Engine: не горит",
                fontWeight = FontWeight.Bold,
                color = if (r.milOn) RED else GREEN
            )
            Text("Кодов по данным ЭБУ: ${r.dtcCount}")
            if (r.items.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Готовность систем", fontWeight = FontWeight.Bold)
                r.items.forEach {
                    Text(
                        "${if (it.complete) "✓" else "✗"} ${it.name}",
                        color = if (it.complete) GREEN else ORANGE,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ScanScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(
                onClick = { vm.runFullScan() },
                enabled = vm.connected && !vm.scanning && !vm.working,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.scanning) "Идёт диагностика…" else "Полная диагностика") }
        }
        if (!vm.connected) {
            item { Text("Сначала подключитесь к адаптеру на вкладке «Подключение». Для лучшего результата заведите двигатель и прогрейте его.") }
        }
        if (vm.scanning) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(progress = { vm.scanProgress }, modifier = Modifier.fillMaxWidth())
                        Text(vm.scanStage)
                    }
                }
            }
        }
        vm.result?.let { r ->
            item {
                val col = when {
                    r.analysis.score >= 85 -> GREEN
                    r.analysis.score >= 60 -> ORANGE
                    else -> RED
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.analysis.score}", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = col)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(r.analysis.verdict, style = MaterialTheme.typography.titleMedium, color = col)
                            Text(r.vehicle, fontSize = 13.sp, color = GRAY)
                            Text(Report.dateText(r.time), fontSize = 13.sp, color = GRAY)
                        }
                    }
                }
            }
            r.readiness?.let { item { ReadinessCard(it) } }
            if (r.analysis.findings.isEmpty()) {
                item { Text("Отклонений не найдено.", color = GREEN) }
            } else {
                item { Text("Выводы", style = MaterialTheme.typography.titleMedium) }
                items(r.analysis.findings) { FindingCard(it) }
            }
            dtcSection("Сохранённые ошибки", r.stored, "сохранённая")
            dtcSection("Ожидающие ошибки", r.pending, "ожидающая")
            if (r.permanent.isNotEmpty()) dtcSection("Постоянные ошибки", r.permanent, "постоянная")
            item {
                Button(onClick = { shareText(ctx, r.text) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Поделиться отчётом")
                }
            }
            item { Text("Отчёт сохранён во вкладке «История».", fontSize = 12.sp, color = GRAY) }
        }
    }
}

@Composable
fun ErrorsScreen(vm: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val busy = vm.working || vm.scanning
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.readErrors() },
                    enabled = vm.connected && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Прочитать") }
                OutlinedButton(
                    onClick = { confirm = true },
                    enabled = vm.connected && vm.errorsRead && !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Сбросить") }
            }
        }
        if (vm.working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.info?.let { t -> item { Card(Modifier.fillMaxWidth()) { Text(t, Modifier.padding(12.dp)) } } }
        if (!vm.connected) item { Text("Нет подключения к адаптеру.") }
        if (vm.errorsRead) {
            vm.readiness?.let { item { ReadinessCard(it) } }
            dtcSection("Сохранённые ошибки", vm.stored, "сохранённая")
            dtcSection("Ожидающие ошибки", vm.pending, "ожидающая")
            if (vm.permanent.isNotEmpty()) dtcSection("Постоянные ошибки", vm.permanent, "постоянная")
            vm.freeze?.let { ff ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Стоп-кадр${ff.dtc?.let { " (код $it)" } ?: ""}",
                                fontWeight = FontWeight.Bold
                            )
                            Text("Состояние двигателя в момент появления ошибки", fontSize = 12.sp, color = GRAY)
                            ff.values.forEach { (pid, v) ->
                                val d = Pids.byId[pid]
                                if (d != null) Text("${d.name}: ${Pids.format(d, v)} ${d.unit}", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    "Текущие ошибки будут сохранены в историю. После сброса обнулятся готовность систем и адаптации ЭБУ. " +
                        "Если причина не устранена, ошибка вернётся."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.clearErrors() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
fun Sparkline(data: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (data.size < 2) return@Canvas
        val mn = data.min()
        val mx = data.max()
        val span = if (mx - mn < 1e-9) 1.0 else mx - mn
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = size.width * i / (data.size - 1)
            val y = size.height - (size.height * ((v - mn) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

@Composable
fun LiveScreen(vm: MainViewModel) {
    val selected = remember { mutableStateListOf<Int>() }
    LaunchedEffect(vm.supported) {
        if (selected.isEmpty()) {
            selected.addAll(listOf(0x0C, 0x05, 0x06, 0x07, 0x14, 0x42).filter { vm.supported.contains(it) })
        }
    }
    val defs = Pids.all.filter { vm.supported.contains(it.pid) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Button(
                onClick = { if (vm.liveRunning) vm.stopLive() else vm.startLive(selected.toList()) },
                enabled = vm.connected && !vm.scanning && !vm.working && (vm.liveRunning || selected.isNotEmpty()),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.liveRunning) "Остановить" else "Запустить опрос") }
        }
        if (!vm.connected) item { Text("Нет подключения к адаптеру.") }
        else if (defs.isEmpty()) item { Text("ЭБУ не сообщил ни об одном поддерживаемом параметре.") }
        else item { Text("Отметьте параметры. Чем их меньше, тем чаще обновляются значения.", fontSize = 12.sp, color = GRAY) }
        items(defs, key = { it.pid }) { d ->
            val isSel = selected.contains(d.pid)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSel, onCheckedChange = { on ->
                            if (on) selected.add(d.pid) else selected.remove(d.pid)
                        })
                        Text(d.name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        val v = vm.live[d.pid]
                        Text(
                            if (v != null) "${Pids.format(d, v)} ${d.unit}" else "—",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    val h = vm.liveHistory[d.pid]
                    if (isSel && h != null && h.size > 1) {
                        Sparkline(h, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(40.dp).padding(bottom = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<SavedReport?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (vm.reports.isEmpty()) item { Text("История пуста. Отчёты появятся после диагностики.") }
        items(vm.reports, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().clickable { open = r }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.Bold)
                        Text(Report.dateText(r.id), fontSize = 12.sp, color = GRAY)
                    }
                    if (r.score >= 0) {
                        Text(
                            "${r.score}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (r.score >= 85) GREEN else if (r.score >= 60) ORANGE else RED
                        )
                    }
                }
            }
        }
    }
    open?.let { r ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("${r.title}, ${Report.dateText(r.id)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(r.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = { TextButton(onClick = { shareText(ctx, r.text) }) { Text("Поделиться") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteReport(r); open = null }) { Text("Удалить") }
                    TextButton(onClick = { open = null }) { Text("Закрыть") }
                }
            }
        )
    }
}

@Composable
fun TerminalScreen(vm: MainViewModel) {
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(vm.terminal.size) {
        if (vm.terminal.isNotEmpty()) state.scrollToItem(vm.terminal.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Для опытных пользователей: команды ELM327 (AT...) и OBD-II (например 0100, 03).", fontSize = 12.sp, color = GRAY)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ATI", "ATDP", "ATRV", "0100", "0101", "03", "07", "0105", "010C").forEach { c ->
                OutlinedButton(onClick = { vm.sendRaw(c) }, enabled = vm.connected) { Text(c) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            items(vm.terminal) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (line.startsWith(">")) MaterialTheme.colorScheme.primary
                    else if (line.startsWith("!")) RED else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Команда") }
            )
            Button(
                onClick = { if (text.isNotBlank()) { vm.sendRaw(text); text = "" } },
                enabled = vm.connected
            ) { Text("Отпр.") }
        }
    }
}

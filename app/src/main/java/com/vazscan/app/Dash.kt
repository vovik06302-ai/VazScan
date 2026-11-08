package com.vazscan.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val DOK = Color(0xFF2E7D32)
private val DWARN = Color(0xFFEF6C00)
private val DALARM = Color(0xFFD32F2F)
private val SCOPE_BG = Color(0xFF0B1410)
private val SCOPE_GRID = Color(0x3300E676)
private val SCOPE_TEXT = Color(0xFFB0BEC5)

fun levelColor(l: Int): Color = when (l) {
    2 -> DALARM
    1 -> DWARN
    else -> DOK
}

data class Alarm(val level: Int, val name: String, val text: String)

/** Пороги для параметров. Уровни: 0 - норма, 1 - внимание, 2 - тревога. */
object Limits {
    fun level(pid: Int, v: Double, running: Boolean): Int = eval(pid, v, running)?.level ?: 0

    fun eval(pid: Int, v: Double, running: Boolean): Alarm? {
        val r: Pair<Int, String> = when (pid) {
            0x0C -> when {
                v > 6500 -> Pair(2, "Обороты выше допустимых: переключите передачу")
                v > 6000 -> Pair(1, "Обороты близко к пределу")
                else -> Pair(0, "")
            }
            0x05 -> when {
                !running -> Pair(0, "")
                v >= 108 -> Pair(2, "Перегрев: остановитесь, дайте остыть, крышку не открывайте")
                v >= 100 -> Pair(1, "Температура выше нормы: проверьте вентилятор и уровень ОЖ")
                v < 70 -> Pair(1, "Двигатель не прогрет: проверьте термостат и датчик ДТОЖ")
                else -> Pair(0, "")
            }
            0x06, 0x07 -> {
                val a = abs(v)
                when {
                    a >= 20 -> Pair(2, "Коррекция топлива вне нормы: подсос воздуха, ДМРВ, давление топлива, форсунки")
                    a >= 10 -> Pair(1, "Коррекция топлива растёт: проверьте подсос воздуха и ДМРВ")
                    else -> Pair(0, "")
                }
            }
            0x42 -> if (running) {
                when {
                    v < 12.5 || v > 15.5 -> Pair(2, "Генератор заряжает неправильно: ремень, щётки, реле-регулятор")
                    v < 13.2 || v > 14.9 -> Pair(1, "Напряжение бортсети вне нормы (норма 13.8-14.5 В)")
                    else -> Pair(0, "")
                }
            } else {
                when {
                    v < 11.8 -> Pair(2, "Аккумулятор сильно разряжен")
                    v < 12.2 -> Pair(1, "Аккумулятор разряжен")
                    else -> Pair(0, "")
                }
            }
            0x04 -> when {
                v >= 95 -> Pair(2, "Нагрузка на пределе")
                v >= 85 -> Pair(1, "Высокая нагрузка на двигатель")
                else -> Pair(0, "")
            }
            0x0B -> if (running && v >= 70) Pair(1, "Давление во впуске высокое: возможен подсос воздуха") else Pair(0, "")
            0x0F -> when {
                v >= 80 -> Pair(2, "Перегрев воздуха на впуске")
                v >= 60 -> Pair(1, "Воздух на впуске горячий")
                else -> Pair(0, "")
            }
            0x2F -> when {
                v < 5 -> Pair(2, "Топлива почти нет")
                v < 12 -> Pair(1, "Мало топлива")
                else -> Pair(0, "")
            }
            else -> Pair(0, "")
        }
        return if (r.first == 0) null else Alarm(r.first, Pids.name(pid), r.second)
    }
}

/** Все текущие проблемы: по плиткам и по форме осциллограммы лямбда-зонда. */
fun alarmsFor(live: Map<Int, Double>, history: Map<Int, List<Double>>): List<Alarm> {
    val running = (live[0x0C] ?: 0.0) > 400
    val out = ArrayList<Alarm>()
    for ((pid, v) in live) {
        Limits.eval(pid, v, running)?.let { out.add(it) }
    }
    val lam = history[0x14] ?: emptyList()
    if (running && lam.size >= 20 && (lam.maxOrNull() ?: 0.0) - (lam.minOrNull() ?: 0.0) < 0.15) {
        out.add(Alarm(2, "Лямбда-зонд 1", "Сигнал почти не меняется: зонд залип или нет прогрева. Проверьте проводку и температуру"))
    }
    return out.sortedByDescending { it.level }
}

@Composable
fun Gauge(title: String, unit: String, value: Double?, min: Double, max: Double, level: Int, decimals: Int) {
    val col = if (value == null) Color.Gray else levelColor(level)
    val track = MaterialTheme.colorScheme.outlineVariant
    val needle = MaterialTheme.colorScheme.onSurface
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 13.sp, textAlign = TextAlign.Center)
        Canvas(Modifier.fillMaxWidth(0.9f).aspectRatio(1f)) {
            val d = size.minDimension
            val stroke = d * 0.07f
            val arcSize = Size(d - 2 * stroke, d - 2 * stroke)
            val tl = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = arcSize.width / 2f
            drawArc(track, 135f, 270f, false, tl, arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
            val frac = if (value == null) 0f else ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            if (value != null) {
                drawArc(col, 135f, 270f * frac, false, tl, arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            for (i in 0..8) {
                val a = Math.toRadians(135.0 + 270.0 * i / 8)
                val p1 = Offset(center.x + (r + stroke * 0.6f) * cos(a).toFloat(), center.y + (r + stroke * 0.6f) * sin(a).toFloat())
                val p2 = Offset(center.x + (r + stroke * 0.95f) * cos(a).toFloat(), center.y + (r + stroke * 0.95f) * sin(a).toFloat())
                drawLine(tick, p1, p2, strokeWidth = 2f)
            }
            val na = Math.toRadians(135.0 + 270.0 * frac)
            val tip = Offset(center.x + (r - stroke * 0.8f) * cos(na).toFloat(), center.y + (r - stroke * 0.8f) * sin(na).toFloat())
            drawLine(needle, center, tip, strokeWidth = d * 0.015f, cap = StrokeCap.Round)
            drawCircle(needle, radius = d * 0.035f, center = center)
        }
        Text(
            if (value == null) "—" else "%.${decimals}f".format(value),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = col
        )
        Text(unit, fontSize = 12.sp, color = SCOPE_TEXT)
    }
}

@Composable
fun Scope(title: String, data: List<Double>, min: Double, max: Double, level: Int, valueText: String) {
    val col = levelColor(level)
    Column(
        Modifier.fillMaxWidth().background(SCOPE_BG, RoundedCornerShape(10.dp)).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SCOPE_TEXT, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = col, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(SCOPE_GRID, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            for (i in 1..9) {
                val x = size.width * i / 10f
                drawLine(SCOPE_GRID, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            if (min < 0 && max > 0) {
                val y = size.height * (1f - ((0 - min) / (max - min)).toFloat())
                drawLine(Color(0x8800E676), Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            }
            if (data.size >= 2) {
                val n = 60
                val path = Path()
                data.forEachIndexed { i, v ->
                    val x = size.width * (n - 1 - (data.size - 1 - i)).toFloat() / (n - 1)
                    val y = size.height * (1f - ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, col, style = Stroke(width = 3f))
            }
        }
    }
}

@Composable
fun Tile(def: PidDef, v: Double?, running: Boolean) {
    val lvl = if (v == null) 0 else Limits.level(def.pid, v, running)
    val col = if (v == null) Color.Gray else levelColor(lvl)
    Card(
        Modifier.fillMaxWidth(),
        border = BorderStroke(if (lvl > 0) 2.dp else 1.dp, col),
        colors = CardDefaults.cardColors(
            containerColor = if (lvl > 0) col.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(def.name, fontSize = 12.sp, maxLines = 2)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (v == null) "—" else Pids.format(def, v),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = col
                )
                Spacer(Modifier.width(4.dp))
                Text(def.unit, fontSize = 12.sp, color = SCOPE_TEXT)
            }
        }
    }
}

@Composable
fun AlarmBanner(alarms: List<Alarm>, live: Boolean) {
    if (!live) {
        Text("Опрос остановлен. Нажмите «Запустить», чтобы видеть состояние в реальном времени.", fontSize = 13.sp)
        return
    }
    if (alarms.isEmpty()) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DOK.copy(alpha = 0.15f))) {
            Text("Всё в норме", Modifier.padding(12.dp), color = DOK, fontWeight = FontWeight.Bold)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        alarms.forEach { a ->
            val c = levelColor(a.level)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.15f))) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        (if (a.level == 2) "ТРЕВОГА: " else "ВНИМАНИЕ: ") + a.name,
                        color = c,
                        fontWeight = FontWeight.Bold
                    )
                    Text(a.text, fontSize = 13.sp)
                }
            }
        }
    }
}

private data class GaugeSpec(val pid: Int, val title: String, val unit: String, val min: Double, val max: Double, val dec: Int)

private val gaugeSpecs = listOf(
    GaugeSpec(0x0C, "Обороты", "об/мин", 0.0, 7000.0, 0),
    GaugeSpec(0x05, "Температура ОЖ", "°C", 40.0, 130.0, 0),
    GaugeSpec(0x42, "Напряжение", "В", 10.0, 16.0, 1),
    GaugeSpec(0x04, "Нагрузка", "%", 0.0, 100.0, 0),
    GaugeSpec(0x0B, "Давление во впуске", "кПа", 20.0, 110.0, 0),
    GaugeSpec(0x11, "Положение дросселя", "%", 0.0, 100.0, 0)
)

private data class ScopeSpec(val pid: Int, val title: String, val min: Double, val max: Double, val dec: Int)

private val scopeSpecs = listOf(
    ScopeSpec(0x14, "Лямбда-зонд 1 (осциллограмма)", 0.0, 1.0, 2),
    ScopeSpec(0x06, "Коррекция топлива кратковременная, %", -25.0, 25.0, 1),
    ScopeSpec(0x07, "Коррекция топлива долговременная, %", -25.0, 25.0, 1)
)

@Composable
fun LiveScreen(vm: MainViewModel) {
    val dashPids = remember(vm.supported) { vm.supported.filter { Pids.byId.containsKey(it) }.sorted() }
    val running = (vm.live[0x0C] ?: 0.0) > 400
    val alarms = alarmsFor(vm.live, vm.liveHistory)
    val tiles = Pids.all.filter { dashPids.contains(it.pid) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(
                onClick = { if (vm.liveRunning) vm.stopLive() else vm.startLive(dashPids) },
                enabled = vm.connected && !vm.scanning && !vm.working && (vm.liveRunning || dashPids.isNotEmpty()),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.liveRunning) "Остановить" else "Запустить опрос") }
        }
        if (!vm.connected) item { Text("Нет подключения к адаптеру.") }
        item { AlarmBanner(alarms, vm.liveRunning) }

        item { Text("Приборы", style = MaterialTheme.typography.titleMedium) }
        items(gaugeSpecs.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { g ->
                    val v = vm.live[g.pid]
                    val lvl = if (v == null) 0 else Limits.level(g.pid, v, running)
                    Box(Modifier.weight(1f)) {
                        Gauge(g.title, g.unit, v, g.min, g.max, lvl, g.dec)
                    }
                }
                if (pair.size < 2) Spacer(Modifier.weight(1f))
            }
        }

        item { Text("Осциллографы", style = MaterialTheme.typography.titleMedium) }
        items(scopeSpecs) { s ->
            val hist = vm.liveHistory[s.pid] ?: emptyList()
            val last = hist.lastOrNull()
            val lvl = when {
                s.pid == 0x14 -> if (alarmsFor(vm.live, vm.liveHistory).any { it.name == "Лямбда-зонд 1" }) 2 else 0
                last != null -> Limits.level(s.pid, last, running)
                else -> 0
            }
            Scope(
                s.title, hist, s.min, s.max, lvl,
                if (last == null) "—" else "%.${s.dec}f".format(last)
            )
        }

        item { Text("Все параметры", style = MaterialTheme.typography.titleMedium) }
        if (tiles.isEmpty() && vm.connected) item { Text("ЭБУ не сообщил ни об одном поддерживаемом параметре.") }
        items(tiles.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { d ->
                    Box(Modifier.weight(1f)) { Tile(d, vm.live[d.pid], running) }
                }
                if (pair.size < 2) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Text(
                "Обновление около 1-2 раз в секунду: ограничение ELM327 по Bluetooth. Для быстрых осциллограмм нужен профессиональный сканер.",
                fontSize = 11.sp,
                color = SCOPE_TEXT
            )
        }
    }
}


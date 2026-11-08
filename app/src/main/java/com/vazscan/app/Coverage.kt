package com.vazscan.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** state: 0 - читается, 1 - частично, 2 - стандарт не предусматривает / ЭБУ не отдаёт, 3 - ошибка */
data class Coverage(val name: String, val state: Int, val note: String)

fun coverageList(sup: Set<Int>, stored: List<String>): List<Coverage> {
    val crank = stored.any { it in listOf("P0335", "P0336", "P0337", "P0338", "P0339") }
    val knock = stored.any { it in listOf("P0325", "P0327", "P0328") }
    fun ok(pid: Int, yes: String, no: String = "ЭБУ не отдаёт этот параметр") =
        if (pid in sup) Pair(0, yes) else Pair(2, no)
    val stdNo = "Стандарт OBD-II не предусматривает. Нужен оригинальный сканер"
    val st06 = ok(0x06, "Есть, см. осциллограф")
    val st07 = ok(0x07, "Есть, см. осциллограф")
    val st42 = ok(0x42, "Есть. При работе двигателя норма 13.8–14.5 В")
    val st10 = ok(0x10, "Есть, расход воздуха в г/с")
    val st2E = ok(0x2E, "Есть, % открытия клапана продувки")
    return listOf(
        Coverage("Кратковременная коррекция топлива", st06.first, st06.second),
        Coverage("Долговременная коррекция топлива", st07.first, st07.second),
        Coverage("Напряжение генератора (бортсеть)", st42.first, st42.second),
        Coverage("Расход воздуха (ДМРВ)", st10.first, st10.second),
        Coverage("Адсорбер: продувка", st2E.first, st2E.second),
        Coverage(
            "Сигнал датчика коленвала",
            if (crank) 3 else if (0x0C in sup) 1 else 2,
            when {
                crank -> "ОШИБКА ДПКВ в памяти. См. карточку ошибки"
                0x0C in sup -> "Видно по оборотам. Прямого сигнала датчика стандарт не даёт"
                else -> "Обороты не получены"
            }
        ),
        Coverage(
            "Детонация",
            if (knock) 3 else 1,
            if (knock) "ОШИБКА датчика детонации в памяти"
            else "Уровень детонации стандарт не передаёт. Видны только коды P0325–P0328"
        ),
        Coverage("Давление кондиционера", 2, stdNo),
        Coverage("Флаг давления усилителя руля", 2, stdNo)
    )
}

@Composable
fun CoverageCard(sup: Set<Int>, stored: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Что видит адаптер", style = MaterialTheme.typography.titleMedium)
            Text(
                "Зелёный — читается, оранжевый — частично, серый — стандарт не передаёт, красный — ошибка",
                fontSize = 12.sp
            )
            coverageList(sup, stored).forEach { c ->
                val col = when (c.state) {
                    3 -> Color(0xFFD32F2F)
                    0 -> Color(0xFF2E7D32)
                    1 -> Color(0xFFEF6C00)
                    else -> Color.Gray
                }
                Column {
                    Text(c.name, fontWeight = FontWeight.Bold, color = col, fontSize = 14.sp)
                    Text(c.note, fontSize = 12.sp)
                }
            }
        }
    }
}

package com.vazscan.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Report {
    fun dateText(t: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(t))

    fun build(
        title: String,
        time: Long,
        vehicle: String,
        adapter: AdapterInfo?,
        vin: String?,
        stored: List<String>,
        pending: List<String>,
        permanent: List<String>,
        readiness: Readiness?,
        freeze: FreezeFrame?,
        snaps: List<LiveSnapshot>,
        lambda: List<Double>,
        analysis: Analysis?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== $title ===")
        sb.appendLine("Дата: ${dateText(time)}")
        sb.appendLine("Автомобиль: $vehicle")
        if (!vin.isNullOrEmpty()) sb.appendLine("VIN: $vin")
        if (adapter != null) {
            sb.appendLine("Адаптер: ${adapter.version}, протокол: ${adapter.protocol}")
        }
        if (analysis != null) {
            sb.appendLine()
            sb.appendLine("ИТОГ: ${analysis.verdict} (оценка ${analysis.score} из 100)")
        }
        if (readiness != null) {
            sb.appendLine("Лампа Check Engine: ${if (readiness.milOn) "ГОРИТ" else "не горит"}; кодов по данным ЭБУ: ${readiness.dtcCount}")
        }

        if (analysis != null && analysis.findings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- ВЫВОДЫ ---")
            for (x in analysis.findings) {
                sb.appendLine("[${sevText(x.severity)}] ${x.title}")
                sb.appendLine("   ${x.details}")
                for (a in x.actions) sb.appendLine("   • $a")
            }
        }

        fun dtcBlock(head: String, codes: List<String>) {
            sb.appendLine()
            sb.appendLine("--- $head ---")
            if (codes.isEmpty()) {
                sb.appendLine("нет")
                return
            }
            for (c in codes) {
                val d = DtcDb.lookup(c)
                sb.appendLine("${d.code} [${sevText(d.severity)}] ${d.title}")
                if (d.causes.isNotEmpty()) {
                    sb.appendLine("   Возможные причины:")
                    d.causes.forEachIndexed { i, s -> sb.appendLine("   ${i + 1}. $s") }
                }
                if (d.steps.isNotEmpty()) {
                    sb.appendLine("   Что делать:")
                    for (s in d.steps) sb.appendLine("   • $s")
                }
            }
        }
        dtcBlock("СОХРАНЁННЫЕ ОШИБКИ", stored)
        dtcBlock("ОЖИДАЮЩИЕ ОШИБКИ", pending)
        if (permanent.isNotEmpty()) dtcBlock("ПОСТОЯННЫЕ ОШИБКИ", permanent)

        if (freeze != null) {
            sb.appendLine()
            sb.appendLine("--- СТОП-КАДР (в момент ошибки${freeze.dtc?.let { ": $it" } ?: ""}) ---")
            for ((pid, v) in freeze.values) {
                val d = Pids.byId[pid] ?: continue
                sb.appendLine("${d.name}: ${Pids.format(d, v)} ${d.unit}")
            }
        }

        if (readiness != null && readiness.items.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- ГОТОВНОСТЬ СИСТЕМ ---")
            for (i in readiness.items) sb.appendLine("${i.name}: ${if (i.complete) "готово" else "не завершено"}")
        }

        if (snaps.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- ЖИВЫЕ ДАННЫЕ (среднее, мин-макс) ---")
            for (pid in Pids.scanList) {
                val l = snaps.mapNotNull { it.v[pid] }
                if (l.isEmpty()) continue
                val d = Pids.byId[pid] ?: continue
                sb.appendLine("${d.name}: ${Pids.format(d, l.average())} ${d.unit} (${Pids.format(d, l.min())} - ${Pids.format(d, l.max())})")
            }
        }
        if (lambda.size >= 5) {
            sb.appendLine("Лямбда-зонд 1: среднее ${"%.2f".format(lambda.average())} В, размах ${"%.2f".format(lambda.min())} - ${"%.2f".format(lambda.max())} В (${lambda.size} замеров)")
        }
        sb.appendLine()
        sb.appendLine("Отчёт создан программой VAZ Scan. Рекомендации ориентировочные: окончательный вывод даёт осмотр и проверка узлов.")
        return sb.toString()
    }

    fun sevText(s: Int) = when (s) {
        3 -> "СЕРЬЁЗНО"
        2 -> "ВНИМАНИЕ"
        else -> "ИНФО"
    }
}

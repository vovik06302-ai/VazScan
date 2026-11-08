package com.vazscan.app

import java.io.IOException

private fun toInts(h: String): IntArray {
    val n = h.length / 2
    return IntArray(n) { h.substring(it * 2, it * 2 + 2).toInt(16) }
}

fun decodeDtc(a: Int, b: Int): String {
    val type = when ((a shr 6) and 3) {
        0 -> 'P'
        1 -> 'C'
        2 -> 'B'
        else -> 'U'
    }
    val d1 = (a shr 4) and 3
    val d2 = a and 0xF
    val d3 = (b shr 4) and 0xF
    val d4 = b and 0xF
    return "%c%d%X%X%X".format(type, d1, d2, d3, d4)
}

class ObdClient(private val t: ObdTransport) {
    var onLog: ((String) -> Unit)? = null
    private val lock = Any()
    private var isCan = false

    private val hexRe = Regex("[0-9A-F]+")
    private val prefixRe = Regex("^[0-9A-F]:")

    fun close() = t.close()

    /** Отправка сырой команды (потокобезопасно, команды идут строго по очереди). */
    fun raw(cmd: String, timeoutMs: Long = 3000): String = synchronized(lock) {
        onLog?.invoke("> $cmd")
        try {
            val r = t.send(cmd, timeoutMs)
            val shown = r.replace('\r', ' ').replace('\n', ' ').trim()
            onLog?.invoke(if (shown.isEmpty()) "(пусто)" else shown)
            r
        } catch (e: IOException) {
            onLog?.invoke("! ${e.message}")
            throw e
        }
    }

    private fun lines(cmd: String, timeoutMs: Long = 3000): List<String> =
        raw(cmd, timeoutMs).split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Только строки, состоящие из HEX-данных (ответы ЭБУ). Ошибки и служебные строки отбрасываются. */
    private fun hexLines(cmd: String, timeoutMs: Long = 4000): List<String> {
        val out = ArrayList<String>()
        for (line in lines(cmd, timeoutMs)) {
            var l = line.replace(" ", "").uppercase()
            l = l.replace(prefixRe, "")
            if (l.isEmpty() || l.length % 2 == 1) continue
            if (!hexRe.matches(l)) continue
            out.add(l)
        }
        return out
    }

    private fun probe(timeoutMs: Long): Boolean =
        hexLines("0100", timeoutMs).any { it.startsWith("4100") }

    fun readVoltage(): Double? =
        try {
            lines("ATRV").firstOrNull()?.uppercase()?.removeSuffix("V")?.trim()?.toDoubleOrNull()
        } catch (e: IOException) {
            null
        }

    fun initialize(): AdapterInfo {
        val notes = ArrayList<String>()
        raw("ATZ", 7000)
        val ati = lines("ATI")
        val version = ati.firstOrNull { it.uppercase().contains("ELM") } ?: ati.firstOrNull() ?: "неизвестно"
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) lines(c)

        lines("ATSP0")
        var ok = probe(25000)
        if (!ok) {
            // Старые блоки ВАЗ (K-line) иногда не находятся в авто-режиме: пробуем протоколы вручную
            for (p in listOf("3", "5", "4")) {
                lines("ATSP$p")
                if (probe(15000)) {
                    ok = true
                    break
                }
            }
        }
        if (!ok) lines("ATSP0")

        val proto = (lines("ATDP").firstOrNull() ?: "?").removePrefix("AUTO, ")
        isCan = proto.uppercase().contains("CAN")
        val volt = readVoltage()

        if (version.uppercase().contains("V2.1") || version.uppercase().contains("V2.2")) {
            notes.add(
                "Адаптер сообщает версию $version. У дешёвых ELM327 это обычно клон прошивки v1.5: " +
                    "основные функции работают, но возможны сбои на некоторых протоколах и при частом опросе."
            )
        }
        if (volt != null && volt < 11.0) {
            notes.add("Напряжение на разъёме OBD низкое (${"%.1f".format(volt)} В). Включите зажигание, проверьте аккумулятор.")
        }
        if (!ok) {
            notes.add(
                "ЭБУ не ответил. Включите зажигание, убедитесь что адаптер вставлен до упора. " +
                    "Часть ВАЗ с блоками Январь 5.1 / 7.2 не поддерживает стандартный OBD-II полностью."
            )
        }
        return AdapterInfo(version, proto, volt, ok, isCan, notes)
    }

    fun readPid(pid: Int): IntArray? {
        val prefix = "41%02X".format(pid)
        val line = hexLines("01%02X".format(pid)).firstOrNull { it.startsWith(prefix) } ?: return null
        return toInts(line.substring(prefix.length))
    }

    fun readValue(def: PidDef): Double? {
        val d = readPid(def.pid) ?: return null
        if (d.size < def.bytes) return null
        return def.calc(d)
    }

    fun supportedPids(): Set<Int> {
        val set = HashSet<Int>()
        var base = 0
        while (base <= 0xC0) {
            val d = readPid(base) ?: break
            if (d.size < 4) break
            val mask = (d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()
            for (i in 1..32) {
                if ((mask and (1L shl (32 - i))) != 0L) set.add(base + i)
            }
            if (!set.contains(base + 0x20)) break
            base += 0x20
        }
        return set
    }

    fun readReadiness(): Readiness? {
        val d = readPid(0x01) ?: return null
        if (d.size < 4) return null
        val a = d[0]
        val b = d[1]
        val c = d[2]
        val dd = d[3]
        val items = ArrayList<ReadyItem>()
        fun cont(name: String, supBit: Int, incBit: Int) {
            val sup = (b and (1 shl supBit)) != 0
            if (sup) items.add(ReadyItem(name, true, (b and (1 shl incBit)) == 0))
        }
        cont("Пропуски зажигания", 0, 4)
        cont("Топливная система", 1, 5)
        cont("Комплектующие (компоненты)", 2, 6)
        val names = listOf(
            "Катализатор", "Подогрев катализатора", "Система улавливания паров (EVAP)", "Вторичный воздух",
            "Кондиционер", "Лямбда-зонд", "Подогрев лямбда-зонда", "Рециркуляция (EGR)"
        )
        for ((i, nm) in names.withIndex()) {
            if ((c and (1 shl i)) != 0) items.add(ReadyItem(nm, true, (dd and (1 shl i)) == 0))
        }
        return Readiness((a and 0x80) != 0, a and 0x7F, items)
    }

    /** mode: "03" сохранённые, "07" ожидающие, "0A" постоянные. null - режим не поддерживается. */
    fun readDtcs(mode: String): List<String>? {
        val resp = "%02X".format(mode.toInt(16) + 0x40)
        val hs = hexLines(mode, 6000)
        val frames = hs.filter { it.startsWith(resp) }
        if (frames.isEmpty()) return null
        val bytes = ArrayList<Int>()
        if (isCan) {
            val all = frames.first() + hs.filter { !it.startsWith(resp) }.joinToString("")
            val b = toInts(all)
            for (i in 2 until b.size) bytes.add(b[i])
        } else {
            for (f in frames) {
                val b = toInts(f)
                for (i in 1 until b.size) bytes.add(b[i])
            }
        }
        val out = LinkedHashSet<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            if (bytes[i] != 0 || bytes[i + 1] != 0) out.add(decodeDtc(bytes[i], bytes[i + 1]))
            i += 2
        }
        return out.toList()
    }

    fun readFreezeFrame(): FreezeFrame? {
        val hs = hexLines("020200")
        val d = hs.firstOrNull { it.startsWith("4202") }?.let { toInts(it.substring(4)) }
        var dtc: String? = null
        if (d != null && d.size >= 3) dtc = decodeDtc(d[1], d[2])
        if (dtc == "P0000") dtc = null
        val vals = LinkedHashMap<Int, Double>()
        for (pid in listOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0F, 0x11)) {
            val def = Pids.byId[pid] ?: continue
            val prefix = "42%02X".format(pid)
            val line = hexLines("02%02X00".format(pid)).firstOrNull { it.startsWith(prefix) } ?: continue
            val data = toInts(line.substring(prefix.length))
            if (data.size >= def.bytes + 1) vals[pid] = def.calc(data.copyOfRange(1, 1 + def.bytes))
        }
        if (dtc == null && vals.isEmpty()) return null
        return FreezeFrame(dtc, vals)
    }

    fun clearDtcs(): Boolean = hexLines("04", 6000).any { it.startsWith("44") }

    fun readVin(): String? =
        try {
            val sb = StringBuilder()
            for (h in hexLines("0902", 6000).filter { it.startsWith("4902") }) {
                val b = toInts(h)
                for (i in 3 until b.size) if (b[i] in 0x30..0x5A) sb.append(b[i].toChar())
            }
            if (sb.length == 17) sb.toString() else null
        } catch (e: Exception) {
            null
        }
}

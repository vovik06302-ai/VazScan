package com.vazscan.app

/** Объяснение ошибки по условиям из стоп-кадра: что происходило в момент появления. */
object FaultContext {
    private fun f(x: Double?): String = x?.let { "%.1f".format(it) } ?: "—"

    fun explain(code: String, ff: FreezeFrame?): List<String> {
        if (ff == null) return listOf("Стоп-кадр не записан: ЭБУ не сохранил условия появления ошибки")
        val rpm = ff.values[0x0C]
        val load = ff.values[0x04]
        val cool = ff.values[0x05]
        val stft = ff.values[0x06]
        val ltft = ff.values[0x07]
        val map = ff.values[0x0B]
        val tps = ff.values[0x11]
        val r = rpm?.toInt()?.toString() ?: "—"
        val out = ArrayList<String>()
        rpm?.let { out.add("Обороты в момент ошибки: ${it.toInt()} об/мин") }
        cool?.let { out.add("Температура ОЖ: ${it.toInt()} °C") }
        load?.let { out.add("Нагрузка: ${it.toInt()} %") }
        when {
            code.startsWith("P030") -> {
                val idle = (rpm ?: 0.0) in 1.0..1000.0
                out.add(
                    when {
                        idle -> "Пропуски на холостом ходу: чаще всего свеча, катушка или подсос воздуха"
                        (load ?: 0.0) > 60 -> "Пропуски под нагрузкой: проверьте давление топлива и свечи под нагрузкой"
                        else -> "Пропуски на режиме движения: проверьте свечи, провода и подачу топлива"
                    }
                )
            }
            code == "P0171" || code == "P0172" ->
                out.add("Коррекция топлива: кратковременная ${f(stft)} %, долговременная ${f(ltft)} %")
            code in listOf("P0115", "P0116", "P0117", "P0118") ->
                out.add("Датчик ОЖ показывал ${f(cool)} °C при $r об/мин")
            code in listOf("P0335", "P0336", "P0337", "P0338", "P0339") ->
                out.add("Сигнал ДПКВ пропал или стал неверным при $r об/мин. Если двигатель работал, датчик или задающий диск дают сбой")
            code in listOf("P0325", "P0327", "P0328") ->
                out.add("Датчик детонации неисправен: ЭБУ не контролирует детонацию при нагрузке ${f(load)} %")
            code in listOf("P0106", "P0107", "P0108") ->
                out.add("Давление во впуске ${f(map)} кПа при $r об/мин")
            code in listOf("P0505", "P0506", "P0507") ->
                out.add("Ошибка холостого хода: дроссель ${f(tps)} %, обороты $r")
            code.startsWith("P013") || code.startsWith("P014") ->
                out.add("Лямбда-зонд не выдавал нормальный сигнал при $r об/мин и ОЖ ${f(cool)} °C")
            else -> {}
        }
        return out
    }
}

/** Ошибки из памяти ЭБУ, которые тоже выводятся тревогой наверх. */
fun dtcAlarms(stored: List<String>): List<Alarm> = stored.mapNotNull { c ->
    val d = DtcDb.lookup(c)
    if (d.severity < 2) null
    else Alarm(d.severity, c, d.title + ". Что делать: " + (d.steps.firstOrNull() ?: "см. карточку ошибки"))
}

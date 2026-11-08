package com.vazscan.app

import kotlin.math.sqrt

object Analyzer {
    private fun vals(s: List<LiveSnapshot>, pid: Int): List<Double> = s.mapNotNull { it.v[pid] }
    private fun avg(l: List<Double>): Double? = if (l.isEmpty()) null else l.average()
    private fun f1(v: Double) = "%.1f".format(v)

    fun analyze(
        stored: List<String>,
        pending: List<String>,
        permanent: List<String>,
        readiness: Readiness?,
        snaps: List<LiveSnapshot>,
        lambda: List<Double>,
        adapterVolt: Double?
    ): Analysis {
        val f = ArrayList<Finding>()
        val all = (stored + pending + permanent).distinct()

        val rpmList = vals(snaps, 0x0C)
        val rpm = avg(rpmList)
        val running = rpm != null && rpm > 400
        val speed = avg(vals(snaps, 0x0D))
        val coolant = avg(vals(snaps, 0x05).takeLast(5))
        val runtime = vals(snaps, 0x1F).lastOrNull()
        val idle = running && (speed == null || speed < 2.0)
        val warm = coolant != null && coolant >= 75.0

        // --- Связь и данные ---
        if (snaps.isEmpty()) {
            f.add(
                Finding(
                    1, "Живые данные не получены",
                    "ЭБУ не отдал значения датчиков по OBD-II. Возможно, выключено зажигание, или блок управления (особенно старые Январь 5.1 / 7.2) не поддерживает эти параметры.",
                    listOf("Включите зажигание и повторите проверку", "Проверьте вкладку «Терминал»: ответ на команду 0100 и 0105")
                )
            )
        } else if (!running) {
            f.add(
                Finding(
                    1, "Двигатель не работал во время проверки",
                    "Анализ топливных коррекций, холостого хода и лямбда-зонда возможен только на работающем двигателе.",
                    listOf("Заведите двигатель, прогрейте до 80 °C и более, затем повторите диагностику")
                )
            )
        }

        // --- Коды ошибок ---
        if (stored.isNotEmpty()) {
            val sev = stored.maxOf { DtcDb.lookup(it).severity }
            f.add(
                Finding(
                    sev, "Сохранённые ошибки: ${stored.size}",
                    stored.joinToString(", "),
                    listOf("Подробные причины и рекомендации по каждому коду приведены ниже", "Сначала устраните причину, потом сбрасывайте ошибки")
                )
            )
        }
        if (pending.isNotEmpty()) {
            f.add(
                Finding(
                    1, "Ожидающие ошибки: ${pending.size}",
                    pending.joinToString(", ") + ". Это неподтверждённые сбои: ЭБУ заметил их, но лампа ещё не загорелась.",
                    listOf("Проследите, не станут ли они постоянными", "Проверьте указанные узлы заранее")
                )
            )
        }
        if (readiness != null && readiness.milOn && stored.isEmpty()) {
            f.add(
                Finding(
                    2, "Лампа Check Engine горит, но сохранённых кодов нет",
                    "Возможно, код относится к системе, недоступной по OBD-II, или ошибки были сброшены.",
                    listOf("Проверьте диагностикой по родному протоколу ВАЗ", "Проверьте вкладку «Ошибки»")
                )
            )
        }

        // --- Температура ---
        if (coolant != null && running) {
            val mx = vals(snaps, 0x05).maxOrNull() ?: coolant
            if (mx >= 108.0) {
                f.add(
                    Finding(
                        3, "Перегрев двигателя", "Температура охлаждающей жидкости до ${f1(mx)} °C.",
                        listOf(
                            "Остановите двигатель и дайте остыть, не открывайте крышку бачка на горячем",
                            "Проверьте уровень ОЖ, работу вентилятора, термостат, радиатор и крышку расширительного бачка"
                        )
                    )
                )
            } else if (mx >= 100.0) {
                f.add(
                    Finding(
                        2, "Повышенная температура двигателя", "Температура ОЖ достигала ${f1(mx)} °C.",
                        listOf("Проверьте включение вентилятора (около 99-104 °C)", "Проверьте уровень ОЖ и чистоту радиатора")
                    )
                )
            } else if (coolant < 75.0 && runtime != null && runtime > 600.0) {
                f.add(
                    Finding(
                        2, "Двигатель не прогревается",
                        "Через ${(runtime / 60).toInt()} мин работы температура ${f1(coolant)} °C.",
                        listOf("Проверьте термостат (открывается около 87 °C)", "Проверьте датчик ДТОЖ: сравните показания с температурой патрубков")
                    )
                )
            }
        }

        // --- Топливные коррекции ---
        val stft = avg(vals(snaps, 0x06))
        val ltft = avg(vals(snaps, 0x07))
        if (running && warm && (stft != null || ltft != null)) {
            val total = (stft ?: 0.0) + (ltft ?: 0.0)
            val txt = "Коррекции: кратковременная ${stft?.let { f1(it) } ?: "—"} %, долговременная ${ltft?.let { f1(it) } ?: "—"} %."
            val lean171 = if (all.contains("P0171")) " Совпадает с кодом P0171." else ""
            val rich172 = if (all.contains("P0172")) " Совпадает с кодом P0172." else ""
            if (total > 12.0) {
                f.add(
                    Finding(
                        if (total > 25.0) 3 else 2, "Смесь бедная (ЭБУ добавляет топливо)", txt + lean171,
                        listOf(
                            "Проверьте подсос воздуха: гофра ДМРВ, шланги, прокладка впускного коллектора, вакуумный усилитель",
                            "Проверьте давление топлива и состояние фильтра",
                            "Проверьте и очистите ДМРВ",
                            "Проверьте форсунки на засорение"
                        )
                    )
                )
            } else if (total < -12.0) {
                f.add(
                    Finding(
                        if (total < -25.0) 3 else 2, "Смесь богатая (ЭБУ убирает топливо)", txt + rich172,
                        listOf(
                            "Проверьте давление топлива (регулятор) и подтекание форсунок",
                            "Проверьте воздушный фильтр",
                            "Проверьте клапан продувки адсорбера",
                            "Проверьте ДТОЖ и ДМРВ"
                        )
                    )
                )
            }
        }

        // --- Напряжение бортсети ---
        val volt = avg(vals(snaps, 0x42)) ?: adapterVolt
        if (volt != null) {
            if (running) {
                if (volt < 13.2) {
                    f.add(
                        Finding(
                            2, "Низкое напряжение бортсети: ${f1(volt)} В",
                            "При работающем двигателе должно быть 13.8-14.5 В. Аккумулятор разряжается.",
                            listOf("Проверьте ремень генератора и его натяжение", "Проверьте щётки, реле-регулятор и контакты генератора", "Проверьте клеммы АКБ и массы")
                        )
                    )
                } else if (volt > 14.9) {
                    f.add(
                        Finding(
                            if (volt > 15.5) 3 else 2, "Повышенное напряжение бортсети: ${f1(volt)} В",
                            "Выше 15 В электроника и аккумулятор перегружаются.",
                            listOf("Проверьте реле-регулятор генератора", "Проверьте массу двигателя и клеммы АКБ")
                        )
                    )
                }
            } else if (volt < 11.8) {
                f.add(
                    Finding(
                        2, "Низкое напряжение аккумулятора: ${f1(volt)} В", "Аккумулятор сильно разряжен или изношен.",
                        listOf("Зарядите аккумулятор и проверьте нагрузочной вилкой", "Проверьте утечки тока")
                    )
                )
            }
        }

        // --- Холостой ход ---
        if (idle && warm && rpm != null) {
            val mean = rpmList.average()
            val sd = sqrt(rpmList.map { (it - mean) * (it - mean) }.average())
            if (rpm > 1000.0) {
                f.add(
                    Finding(
                        2, "Повышенные обороты холостого хода: ${rpm.toInt()} об/мин",
                        "Для прогретого 1.5 л 8 кл. обычно 750-900 об/мин.",
                        listOf("Проверьте подсос воздуха", "Проверьте дроссель и тросик газа (должен полностью закрываться)", "Проверьте РХХ и ДПДЗ")
                    )
                )
            } else if (rpm < 650.0) {
                f.add(
                    Finding(
                        2, "Пониженные обороты холостого хода: ${rpm.toInt()} об/мин", "Двигатель может глохнуть.",
                        listOf("Почистите дроссельный узел и канал РХХ", "Проверьте давление топлива, свечи и зажигание")
                    )
                )
            }
            if (sd > 60.0) {
                f.add(
                    Finding(
                        2, "Плавающие обороты холостого хода", "Отклонение около ${sd.toInt()} об/мин вокруг ${mean.toInt()}.",
                        listOf("Проверьте подсос воздуха и РХХ", "Проверьте свечи, провода и форсунки на пропуски", "Почистите дроссель")
                    )
                )
            }
        }

        // --- ДМРВ на холостом ---
        val maf = avg(vals(snaps, 0x10))
        if (idle && warm && maf != null && (maf < 1.5 || maf > 4.5)) {
            f.add(
                Finding(
                    2, "Расход воздуха на холостом нетипичный: ${f1(maf)} г/с",
                    "Для 1.5 л на прогретом холостом обычно около 2-3.5 г/с.",
                    listOf("Очистите или проверьте ДМРВ", "Проверьте подсос воздуха и состояние воздушного фильтра", "Проверьте дроссель и РХХ")
                )
            )
        }

        // --- Дроссель ---
        val tps = avg(vals(snaps, 0x11))
        if (idle && tps != null && tps > 5.0) {
            f.add(
                Finding(
                    2, "Дроссель на холостом приоткрыт: ${f1(tps)} %", "Заслонка должна быть закрыта (около 0-2 %).",
                    listOf("Проверьте тросик и педаль газа", "Почистите дроссельный узел", "Проверьте ДПДЗ")
                )
            )
        }

        // --- Лямбда-зонд ---
        if (running && warm && lambda.size >= 15) {
            val mn = lambda.minOrNull() ?: 0.0
            val mx = lambda.maxOrNull() ?: 0.0
            val mean = lambda.average()
            var crossings = 0
            for (i in 1 until lambda.size) {
                if ((lambda[i - 1] - 0.45) * (lambda[i] - 0.45) < 0) crossings++
            }
            if (mx - mn < 0.25) {
                val state = when {
                    mean > 0.6 -> "залип на богатой смеси"
                    mean < 0.3 -> "залип на бедной смеси"
                    else -> "почти не колеблется"
                }
                f.add(
                    Finding(
                        2, "Лямбда-зонд 1: сигнал $state",
                        "Размах сигнала ${f1(mx - mn)} В (у исправного зонда 0.1-0.9 В).",
                        listOf("Проверьте проводку и колодку зонда", "Проверьте подсос воздуха и давление топлива", "Замените зонд, если он старше 80-100 тыс. км")
                    )
                )
            } else if (crossings < 6) {
                f.add(
                    Finding(
                        1, "Лямбда-зонд 1: вялая реакция (возможно)",
                        "Сигнал пересёк средний уровень $crossings раз за время проверки.",
                        listOf("Сравните с повторным замером при другой нагрузке", "При подтверждении замените зонд")
                    )
                )
            }
        }

        // --- Готовность систем ---
        if (readiness != null) {
            val inc = readiness.items.filter { !it.complete }
            if (inc.isNotEmpty()) {
                f.add(
                    Finding(
                        1, "Тесты готовности не завершены: ${inc.size}",
                        inc.joinToString(", ") { it.name } + ". Обычно так бывает после сброса ошибок или отключения АКБ.",
                        listOf("Проедьте несколько циклов прогрева и движения, затем проверьте снова")
                    )
                )
            }
        }

        // --- Подтверждение кодов датчика температуры ---
        if (all.any { it in listOf("P0115", "P0117", "P0118") } && coolant != null && (coolant <= -35.0 || coolant >= 130.0)) {
            f.add(
                Finding(
                    2, "Показания ДТОЖ подтверждают неисправность датчика",
                    "Датчик показывает ${f1(coolant)} °C, что нереально.",
                    listOf("Проверьте провода и колодку ДТОЖ", "Замените датчик")
                )
            )
        }

        // --- Итоговая оценка ---
        var score = 100
        for (x in f) score -= when (x.severity) { 3 -> 25; 2 -> 10; else -> 2 }
        for (c in stored) score -= if (DtcDb.lookup(c).severity >= 3) 15 else 8
        score = score.coerceIn(0, 100)
        val verdict = when {
            score >= 85 -> "Хорошее состояние"
            score >= 60 -> "Есть замечания"
            else -> "Требуется ремонт"
        }
        return Analysis(score, verdict, f.sortedByDescending { it.severity })
    }
}

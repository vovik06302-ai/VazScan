package com.vazscan.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Голос на встроенном синтезаторе Android. Работает без интернета, если установлен русский голос. */
class Speaker(ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled = true

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ru"))
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun say(text: String) {
        if (!enabled || !ready) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "vaz-${System.nanoTime()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

object Phrases {
    private fun spell(code: String) = code.toList().joinToString(" ")

    fun scan(r: ScanResult): String {
        val out = ArrayList<String>()
        out.add("Диагностика завершена. ${r.analysis.verdict}. Оценка ${r.analysis.score} из 100.")
        val codes = (r.stored + r.pending).distinct()
        if (codes.isEmpty()) {
            out.add("Ошибок не найдено.")
        } else {
            out.add("Найдено ошибок: ${codes.size}.")
            codes.take(3).forEach { c -> out.add("Код ${spell(c)}. ${DtcDb.lookup(c).title}.") }
            if (codes.size > 3) out.add("И ещё ${codes.size - 3}. Подробности на экране.")
        }
        r.analysis.findings.take(2).forEach { out.add("${it.title}.") }
        return out.joinToString(" ")
    }
}

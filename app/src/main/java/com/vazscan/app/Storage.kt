package com.vazscan.app

import org.json.JSONObject
import java.io.File

data class SavedReport(val id: Long, val title: String, val score: Int, val text: String)

class ReportStore(private val dir: File) {
    fun list(): List<SavedReport> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.extension == "json" }.mapNotNull {
            try {
                val o = JSONObject(it.readText())
                SavedReport(o.getLong("id"), o.getString("title"), o.getInt("score"), o.getString("text"))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.id }
    }

    fun save(r: SavedReport) {
        dir.mkdirs()
        val o = JSONObject()
        o.put("id", r.id)
        o.put("title", r.title)
        o.put("score", r.score)
        o.put("text", r.text)
        File(dir, "${r.id}.json").writeText(o.toString())
    }

    fun delete(id: Long) {
        File(dir, "$id.json").delete()
    }
}

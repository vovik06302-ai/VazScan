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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VehicleInfo(
    val vin: String?,
    val make: String?,
    val model: String?,
    val year: Int?,
    val engine: String?,
    val country: String?,
    val odometerKm: Double?,
    val ecuName: String?,
    val calibrationId: String?,
    val notes: List<String>
)

fun VehicleInfo.displayName(): String =
    listOfNotNull(make, model, year?.toString()).joinToString(" ").ifBlank { "Автомобиль" }

private fun JSONObject.str(k: String): String? =
    optString(k).takeIf { it.isNotBlank() && it != "null" && it != "Not Applicable" }

object VinDecoder {
    private val wmi3 = mapOf(
        "XTA" to "Lada (ВАЗ)", "XTT" to "Lada (ВАЗ)", "XTH" to "Lada (ВАЗ)", "X9F" to "Lada (ВАЗ)",
        "XW8" to "Volkswagen (Россия)", "KL1" to "Daewoo/Chevrolet", "KLA" to "Daewoo",
        "WVW" to "Volkswagen", "WVG" to "Volkswagen", "WAU" to "Audi", "TRU" to "Audi",
        "WBA" to "BMW", "WBS" to "BMW M", "WDD" to "Mercedes-Benz", "WDB" to "Mercedes-Benz",
        "JTD" to "Toyota", "JTE" to "Toyota", "JTM" to "Toyota", "SB1" to "Toyota", "MR0" to "Toyota",
        "JHM" to "Honda", "JHL" to "Honda", "1HG" to "Honda",
        "JMZ" to "Mazda", "JM1" to "Mazda", "JN1" to "Nissan", "JN8" to "Nissan", "SJN" to "Nissan",
        "JS3" to "Suzuki", "JF1" to "Subaru",
        "KMH" to "Hyundai", "KMF" to "Hyundai", "TMA" to "Hyundai", "Z94" to "Hyundai",
        "KNA" to "Kia", "U5Y" to "Kia",
        "TMB" to "Skoda", "TMP" to "Skoda", "VSS" to "SEAT",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "UU1" to "Dacia",
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "1FA" to "Ford", "1FT" to "Ford", "WF0" to "Ford",
        "YV1" to "Volvo", "SAL" to "Land Rover", "SAJ" to "Jaguar",
        "ZFA" to "Fiat", "ZAR" to "Alfa Romeo", "ZFF" to "Ferrari",
        "LSV" to "SAIC/Volkswagen (Китай)", "LVV" to "Chery", "LFV" to "FAW-VW"
    )

    private val wmi2 = mapOf(
        "JT" to "Toyota", "JH" to "Honda", "JN" to "Nissan", "JM" to "Mazda", "KM" to "Hyundai",
        "WV" to "Volkswagen", "WA" to "Audi", "WB" to "BMW", "WD" to "Mercedes-Benz",
        "VF" to "France", "TM" to "Skoda", "ZF" to "Fiat", "YV" to "Volvo", "SA" to "Land Rover/Jaguar",
        "1G" to "General Motors", "1F" to "Ford"
    )

    private val ladaModels = mapOf(
        "2101" to "2101", "2105" to "2105", "2107" to "2107", "2108" to "2108", "2109" to "2109",
        "2110" to "2110", "2111" to "2111", "2112" to "2112", "2113" to "2113", "2114" to "2114",
        "2115" to "2115", "2123" to "Niva 2123", "2170" to "Priora 2170", "2172" to "Kalina 2172",
        "2190" to "Granta 2190"
    )

    private val regions = mapOf(
        '1' to "США", '4' to "США", '5' to "США", '2' to "Канада", '3' to "Мексика",
        'J' to "Япония", 'K' to "Корея", 'L' to "Китай", 'T' to "Европа", 'V' to "Европа",
        'W' to "Германия", 'X' to "Россия", 'Y' to "Скандинавия/Финляндия", 'Z' to "Италия",
        'S' to "Великобритания", 'Z' to "Италия"
    )

    fun wmi(vin: String): String? = wmi3[vin.take(3)] ?: wmi2[vin.take(2)]

    fun yearFromVin(vin: String): Int? {
        val c = vin[9]
        if (c in '1'..'9') return 2000 + (c - '0')
        val idx = "ABCDEFGHJKLMNPRSTVWXY".indexOf(c)
        if (idx < 0) return null
        val y = 2010 + idx
        return if (y > 2027) y - 30 else y
    }

    private fun online(vin: String): JSONObject? = try {
        val c = URL("https://vpic.nhtsa.dot.gov/api/vehicles/decodevinvaluesextended/$vin?format=json")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        if (c.responseCode != 200) null
        else JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("Results").getJSONObject(0)
    } catch (e: Exception) {
        null
    }

    fun build(vin: String?, odometerKm: Double?, ecuName: String?, calibrationId: String?): VehicleInfo {
        val notes = ArrayList<String>()
        val v = vin?.takeIf { it.length == 17 && it.none { ch -> ch == 'I' || ch == 'O' || ch == 'Q' } }
        if (vin != null && v == null) notes.add("VIN прочитан не полностью, данные по нему могут быть неточными")

        val localMake = v?.let { wmi(it) }
        val localYear = v?.let { yearFromVin(it) }
        val localModel = v?.let { if (it.take(3) in listOf("XTA", "XTT", "XTH", "X9F")) ladaModels[it.substring(3, 7)] else null }
        val on = v?.let { online(it) }
        if (v != null && on == null) notes.add("Нет интернета: модель и двигатель по базе VIN не определены")

        val make = on?.str("Make") ?: localMake
        val model = on?.str("Model") ?: localModel
        val year = on?.str("ModelYear")?.toIntOrNull() ?: localYear
        val engine = on?.let { o ->
            listOfNotNull(
                o.str("DisplacementL")?.let { "$it л" },
                o.str("EngineCylinders")?.let { "$it цил." },
                o.str("FuelTypePrimary")
            ).joinToString(", ").ifBlank { null }
        }
        val country = v?.let { regions[it[0]] }

        if (odometerKm == null) notes.add("ЭБУ не передаёт пробег по стандарту OBD-II. Для многих машин до ~2008 года это нормально")
        if (ecuName == null) notes.add("ЭБУ не назвал себя: этот запрос поддерживают не все блоки")

        return VehicleInfo(vin = v ?: vin, make = make, model = model, year = year, engine = engine,
            country = country, odometerKm = odometerKm, ecuName = ecuName,
            calibrationId = calibrationId, notes = notes)
    }
}

@Composable
fun VehicleCard(v: VehicleInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(v.displayName(), style = MaterialTheme.typography.titleMedium)
            v.engine?.let { Text("Двигатель: $it") }
            v.country?.let { Text("Страна производства: $it") }
            Text("Пробег: " + (v.odometerKm?.let { "%.0f км".format(it) } ?: "ЭБУ не передаёт"))
            v.vin?.let { Text("VIN: $it") }
            Text("ЭБУ: " + (v.ecuName ?: "не определён"))
            v.calibrationId?.let { Text("Калибровка: $it") }
            v.notes.forEach { Text(it, color = Color(0xFFEF6C00), fontSize = 12.sp) }
        }
    }
}

package com.vazscan.app

data class AdapterInfo(
    val version: String = "",
    val protocol: String = "",
    val voltage: Double? = null,
    val ecuResponds: Boolean = false,
    val isCan: Boolean = false,
    val notes: List<String> = emptyList()
)

data class ReadyItem(val name: String, val supported: Boolean, val complete: Boolean)

data class Readiness(val milOn: Boolean, val dtcCount: Int, val items: List<ReadyItem>)

data class FreezeFrame(val dtc: String?, val values: Map<Int, Double>)

data class LiveSnapshot(val t: Long, val v: Map<Int, Double>)

/** severity: 1 - информация, 2 - предупреждение, 3 - серьёзно (не ехать / срочно проверить) */
data class Finding(
    val severity: Int,
    val title: String,
    val details: String,
    val actions: List<String>
)

data class Analysis(val score: Int, val verdict: String, val findings: List<Finding>)

data class ScanResult(
    val time: Long,
    val vehicle: String,
    val adapter: AdapterInfo?,
    val vin: String?,
    val stored: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val readiness: Readiness?,
    val freeze: FreezeFrame?,
    val snaps: List<LiveSnapshot>,
    val lambda: List<Double>,
    val analysis: Analysis,
    val text: String
)

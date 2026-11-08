package com.vazscan.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    // --- подключение ---
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("Не подключено")
    var adapter by mutableStateOf<AdapterInfo?>(null)
    var supported by mutableStateOf<Set<Int>>(emptySet())
    var vin by mutableStateOf<String?>(null)
    var vehicleInfo by mutableStateOf<VehicleInfo?>(null)
    var demo by mutableStateOf(false)
    var vehicle by mutableStateOf("Автомобиль не определён")
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var voice by mutableStateOf(true)

    // --- терминал ---
    val terminal = mutableStateListOf<String>()

    // --- диагностика ---
    var scanning by mutableStateOf(false)
    var scanStage by mutableStateOf("")
    var scanProgress by mutableStateOf(0f)
    var result by mutableStateOf<ScanResult?>(null)

    // --- ошибки ---
    var working by mutableStateOf(false)
    var errorsRead by mutableStateOf(false)
    var stored by mutableStateOf<List<String>>(emptyList())
    var pending by mutableStateOf<List<String>>(emptyList())
    var permanent by mutableStateOf<List<String>>(emptyList())
    var readiness by mutableStateOf<Readiness?>(null)
    var freeze by mutableStateOf<FreezeFrame?>(null)

    // --- датчики ---
    var liveRunning by mutableStateOf(false)
    var live by mutableStateOf<Map<Int, Double>>(emptyMap())
    val liveHistory = mutableStateMapOf<Int, List<Double>>()

    // --- история ---
    val reports = mutableStateListOf<SavedReport>()

    private var client: ObdClient? = null
    private val speaker = Speaker(app)
    private val spoken = HashSet<String>()
    private var liveJob: Job? = null
    private val store = ReportStore(File(app.filesDir, "reports"))

    init {
        reports.addAll(store.list())
    }

    private fun log(line: String) {
        viewModelScope.launch(Dispatchers.Main) {
            terminal.add(line)
            if (terminal.size > 400) terminal.removeAt(0)
        }
    }

    private fun markLost(e: Exception) {
        error = "Связь потеряна: ${e.message}"
        connected = false
        status = "Связь потеряна"
        liveRunning = false
    }

    // ---------- подключение ----------
    fun connectBt(device: BluetoothDevice) = connectWith(false) { BtTransport(device) }

    fun connectDemo() = connectWith(true) { DemoTransport() }

    private fun connectWith(isDemo: Boolean, factory: () -> ObdTransport) {
        if (busy) return
        disconnect()
        busy = true
        status = "Подключение…"
        error = null
        info = null
        viewModelScope.launch(Dispatchers.IO) {
            var c: ObdClient? = null
            try {
                val t = factory()
                t.open()
                c = ObdClient(t)
                c.onLog = { line -> log(line) }
                status = "Инициализация адаптера (поиск ЭБУ может занять до минуты)…"
                val i = c.initialize()
                client = c
                adapter = i
                demo = isDemo
                connected = true
                status = if (i.ecuResponds) "Подключено к ЭБУ" else "Адаптер подключён, ЭБУ не отвечает"
                if (i.ecuResponds) {
                    supported = c.supportedPids()
                    vin = c.readVin()
                    vehicleInfo = VinDecoder.build(vin, c.readOdometer(), c.readMode09Text(0x0A), c.readMode09Text(0x04))
                    vehicle = vehicleInfo?.displayName() ?: "Автомобиль не определён"
                }
            } catch (e: Exception) {
                error = "Ошибка подключения: ${e.message}"
                status = "Не подключено"
                connected = false
                try {
                    c?.close()
                } catch (e2: Exception) {
                }
                client = null
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        liveJob?.cancel()
        liveJob = null
        liveRunning = false
        try {
            client?.close()
        } catch (e: Exception) {
        }
        client = null
        connected = false
        adapter = null
        supported = emptySet()
        vin = null
        vehicleInfo = null
        vehicle = "Автомобиль не определён"
        demo = false
        status = "Не подключено"
    }

    // ---------- сырые команды ----------
    fun sendRaw(cmd: String) {
        val c = client ?: run {
            info = "Нет подключения"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                c.raw(cmd.trim().uppercase(), 8000)
            } catch (e: Exception) {
                markLost(e)
            }
        }
    }

    // ---------- живые данные ----------
    fun startLive(pids: List<Int>) {
        val c = client ?: return
        if (liveRunning || scanning || working || pids.isEmpty()) return
        liveRunning = true
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val m = HashMap<Int, Double>()
                    for (p in pids) {
                        if (!isActive) break
                        val def = Pids.byId[p] ?: continue
                        val v = c.readValue(def)
                        if (v != null) m[p] = v
                    }
                    live = live + m
                    for ((p, v) in m) {
                        liveHistory[p] = (liveHistory[p] ?: emptyList()).takeLast(59) + v
                    }
                    if (voice) speakNewAlarms()
                }
            } catch (e: Exception) {
                markLost(e)
            } finally {
                liveRunning = false
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        liveRunning = false
    }

    // ---------- ошибки ----------
    private fun runIo(block: (ObdClient) -> Unit) {
        val c = client ?: return
        if (scanning || working) return
        stopLive()
        working = true
        info = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(c)
            } catch (e: Exception) {
                markLost(e)
            } finally {
                working = false
            }
        }
    }

    private fun readErrorsNow(c: ObdClient) {
        stored = c.readDtcs("03") ?: emptyList()
        pending = c.readDtcs("07") ?: emptyList()
        permanent = c.readDtcs("0A") ?: emptyList()
        readiness = c.readReadiness()
        freeze = if (stored.isNotEmpty() || pending.isNotEmpty()) c.readFreezeFrame() else null
        errorsRead = true
    }

    fun readErrors() = runIo { c -> readErrorsNow(c) }

    fun clearErrors() = runIo { c ->
        if (stored.isNotEmpty() || pending.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val text = Report.build(
                "Ошибки перед сбросом", now, vehicle, adapter, vin, stored, pending, permanent,
                readiness, freeze, emptyList(), emptyList(), null
            )
            saveReport(SavedReport(now, "Ошибки перед сбросом", -1, text))
        }
        val ok = c.clearDtcs()
        info = if (ok) {
            "Ошибки сброшены. Запустите двигатель и проверьте, вернулись ли они. Если вернулись, причина не устранена."
        } else {
            "ЭБУ не подтвердил сброс (при выключенном зажигании или на старых блоках сброс может не поддерживаться)."
        }
        readErrorsNow(c)
    }

    // ---------- полная диагностика ----------
    private fun stage(text: String, p: Float) {
        scanStage = text
        scanProgress = p
    }

    fun runFullScan() {
        val c = client ?: return
        if (scanning || working) return
        stopLive()
        scanning = true
        scanProgress = 0f
        result = null
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stage("Проверка связи и напряжения", 0.05f)
                val volt = c.readVoltage()

                stage("Определение поддерживаемых датчиков", 0.12f)
                val sup = c.supportedPids()
                supported = sup

                stage("Лампа Check Engine и готовность систем", 0.2f)
                val rd = c.readReadiness()

                stage("Чтение кодов ошибок", 0.3f)
                val st = c.readDtcs("03") ?: emptyList()
                val pe = c.readDtcs("07") ?: emptyList()
                val pm = c.readDtcs("0A") ?: emptyList()

                stage("Стоп-кадр неисправности", 0.4f)
                val ff = if (st.isNotEmpty() || pe.isNotEmpty()) c.readFreezeFrame() else null

                stage("Живые данные (двигатель должен работать)", 0.5f)
                val pids = Pids.scanList.filter { sup.contains(it) }
                val snaps = ArrayList<LiveSnapshot>()
                val t0 = System.currentTimeMillis()
                while (pids.isNotEmpty() && System.currentTimeMillis() - t0 < 12000) {
                    val m = HashMap<Int, Double>()
                    for (p in pids) {
                        val v = c.readValue(Pids.byId.getValue(p))
                        if (v != null) m[p] = v
                    }
                    if (m.isNotEmpty()) snaps.add(LiveSnapshot(System.currentTimeMillis(), m))
                    scanProgress = 0.5f + 0.3f * ((System.currentTimeMillis() - t0) / 12000f).coerceAtMost(1f)
                }

                stage("Анализ лямбда-зонда", 0.8f)
                val lam = ArrayList<Double>()
                if (sup.contains(0x14)) {
                    val t1 = System.currentTimeMillis()
                    val def = Pids.byId.getValue(0x14)
                    while (System.currentTimeMillis() - t1 < 12000) {
                        val v = c.readValue(def)
                        if (v != null) lam.add(v)
                        scanProgress = 0.8f + 0.15f * ((System.currentTimeMillis() - t1) / 12000f).coerceAtMost(1f)
                    }
                }

                stage("Анализ результатов", 0.96f)
                val analysis = Analyzer.analyze(st, pe, pm, rd, snaps, lam, volt)
                val now = System.currentTimeMillis()
                val text = Report.build(
                    "Полная диагностика", now, vehicle, adapter, vin, st, pe, pm, rd, ff, snaps, lam, analysis
                )
                val res = ScanResult(now, vehicle, adapter, vin, st, pe, pm, rd, ff, snaps, lam, analysis, text)
                result = res
                if (voice) speaker.say(Phrases.scan(res))
                stored = st
                pending = pe
                permanent = pm
                readiness = rd
                freeze = ff
                errorsRead = true
                saveReport(SavedReport(now, "Полная диагностика", analysis.score, text))
                stage("Готово", 1f)
            } catch (e: Exception) {
                markLost(e)
            } finally {
                scanning = false
            }
        }
    }

    // ---------- история ----------
    private fun saveReport(r: SavedReport) {
        store.save(r)
        viewModelScope.launch(Dispatchers.Main) { reports.add(0, r) }
    }

    fun deleteReport(r: SavedReport) {
        store.delete(r.id)
        reports.remove(r)
    }

    fun setVoice(on: Boolean) {
        voice = on
        speaker.enabled = on
    }

    fun speakResult() {
        result?.let { speaker.say(Phrases.scan(it)) }
    }

    /** Озвучивает каждую красную тревогу один раз, пока она не исчезнет. */
    private fun speakNewAlarms() {
        val now = alarmsFor(live, liveHistory).filter { it.level == 2 }
        for (a in now) if (spoken.add(a.name)) speaker.say("Тревога. ${a.name}. ${a.text}")
        spoken.retainAll(now.map { it.name }.toSet())
    }

    override fun onCleared() {
        disconnect()
        speaker.shutdown()
        super.onCleared()
    }
}

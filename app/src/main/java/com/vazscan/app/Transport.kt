package com.vazscan.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.sin

/** Любой канал связи с адаптером. Позже сюда можно добавить USB, Wi-Fi, BLE для оригинального сканера. */
interface ObdTransport {
    fun open()
    fun close()
    fun send(cmd: String, timeoutMs: Long = 3000): String
}

class BtTransport(private val device: BluetoothDevice) : ObdTransport {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    override fun open() {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        var s: BluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
        try {
            s.connect()
        } catch (e: IOException) {
            try {
                s.close()
            } catch (e2: Exception) {
            }
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            s = m.invoke(device, 1) as BluetoothSocket
            s.connect()
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    override fun send(cmd: String, timeoutMs: Long): String {
        val o = output ?: throw IOException("Нет соединения")
        val i = input ?: throw IOException("Нет соединения")
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray())
        o.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (i.available() > 0) {
                val c = i.read()
                if (c < 0) throw IOException("Соединение закрыто адаптером")
                val ch = c.toChar()
                if (ch == '>') return sb.toString()
                sb.append(ch)
            } else {
                Thread.sleep(8)
            }
        }
        throw IOException("Нет ответа адаптера на команду $cmd")
    }
}

/** Имитация ВАЗ 2112 с ошибками P0171, P0135 и ожидающей P0300 - для проверки программы без автомобиля. */
class DemoTransport : ObdTransport {
    private var isOpen = false
    private var cleared = false
    private val rnd = java.util.Random()
    private var tick = 0
    private var lamTick = 0

    private val sup = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x13, 0x14, 0x15, 0x1C, 0x1F, 0x20, 0x21, 0x40, 0x42
    )

    override fun open() {
        Thread.sleep(700)
        isOpen = true
    }

    override fun close() {
        isOpen = false
    }

    private fun maskFor(base: Int): String {
        var m = 0L
        for (i in 1..32) if (sup.contains(base + i)) m = m or (1L shl (32 - i))
        return "%08X".format(m)
    }

    private fun b1(v: Double) = "%02X".format(v.toInt().coerceIn(0, 255))
    private fun b2(v: Double) = "%04X".format(v.toInt().coerceIn(0, 65535))

    private fun pidData(pid: Int): String? {
        val n = rnd.nextGaussian()
        return when (pid) {
            0x04 -> b1((28.0 + n) * 255 / 100)
            0x05 -> b1(89.0 + 40)
            0x06 -> b1(128 + (6.0 + n * 2) * 128 / 100)
            0x07 -> b1(128 + 14.0 * 128 / 100)
            0x0B -> b1(36.0 + n)
            0x0C -> b2((830 + n * 12) * 4)
            0x0D -> b1(0.0)
            0x0E -> b1((14.0 + n * 0.5 + 64) * 2)
            0x0F -> b1(27.0 + 40)
            0x10 -> b2((2.9 + n * 0.05) * 100)
            0x11 -> b1(0.8 * 255 / 100)
            0x14 -> {
                lamTick++
                val v = 0.45 + 0.35 * sin(lamTick * 0.9) + n * 0.02
                b1(v * 200) + "FF"
            }
            0x15 -> b1((0.65 + n * 0.02) * 200) + "FF"
            0x1F -> b2(1260.0 + tick)
            0x21 -> b2(42.0)
            0x42 -> b2((14.02 + n * 0.03) * 1000)
            else -> null
        }
    }

    override fun send(cmd: String, timeoutMs: Long): String {
        if (!isOpen) throw IOException("Нет соединения")
        Thread.sleep(60)
        tick++
        val c = cmd.trim().uppercase().replace(" ", "")
        return when {
            c == "ATZ" -> "\rELM327 v1.5\r"
            c == "ATI" -> "ELM327 v1.5\r"
            c == "ATDP" -> "AUTO, ISO 9141-2\r"
            c == "ATRV" -> "14.0V\r"
            c.startsWith("AT") -> "OK\r"
            c == "0100" -> "SEARCHING...\r4100" + maskFor(0) + "\r"
            c == "0120" -> "4120" + maskFor(0x20) + "\r"
            c == "0140" -> "4140" + maskFor(0x40) + "\r"
            c == "0160" || c == "0180" || c == "01A0" || c == "01C0" -> "NO DATA\r"
            c == "0101" -> (if (cleared) "410100076100" else "410182076101") + "\r"
            c == "03" -> (if (cleared) "43000000000000" else "43017101350000") + "\r"
            c == "07" -> (if (cleared) "47000000000000" else "47030000000000") + "\r"
            c == "0A" -> "NO DATA\r"
            c == "04" -> {
                cleared = true
                "44\r"
            }
            c == "0902" -> "NO DATA\r"
            c == "020200" -> (if (cleared) "NO DATA" else "4202000171") + "\r"
            c.length == 6 && c.startsWith("02") && c.endsWith("00") -> {
                val pid = c.substring(2, 4).toInt(16)
                val d = if (cleared) null else pidData(pid)
                if (d == null) "NO DATA\r" else "42" + "%02X".format(pid) + "00" + d + "\r"
            }
            c.length == 4 && c.startsWith("01") -> {
                val pid = c.substring(2, 4).toInt(16)
                val d = pidData(pid)
                if (d == null) "NO DATA\r" else "41" + "%02X".format(pid) + d + "\r"
            }
            else -> "?\r"
        }
    }
}

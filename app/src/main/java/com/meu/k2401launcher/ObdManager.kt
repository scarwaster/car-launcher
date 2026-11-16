package com.meu.k2401launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ObdData(
    val connected: Boolean = false,
    val statusText: String = "OBD Off",
    val coolantTempC: Int? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val loadPct: Int? = null,
    val instantKmL: Double? = null,
    val avgKmL: Double? = null,
    val litersPerHour: Double? = null,
    val gear: String = "--",
    val voltage: Double? = null
)

class ObdManager(
    private val adapter: BluetoothAdapter?,
    private val onUpdate: (ObdData) -> Unit
) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var workerThread: Thread? = null
    private var socket: BluetoothSocket? = null

    @Volatile var isEthanol: Boolean = false
    @Volatile private var tripKm: Double = 0.0
    @Volatile private var tripLiters: Double = 0.0

    fun resetTrip() {
        tripKm = 0.0
        tripLiters = 0.0
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        disconnect()
        if (adapter == null || !adapter.isEnabled) {
            postUpdate(ObdData(connected = false, statusText = "BT Desligado"))
            return
        }

        running.set(true)
        workerThread = Thread {
            while (running.get()) {
                try {
                    postUpdate(ObdData(connected = false, statusText = "Conectando..."))
                    val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)

                    socket = try {
                        val s = device.createRfcommSocketToServiceRecord(sppUuid)
                        s.connect()
                        s
                    } catch (_: Exception) {
                        // Fallback muito comum para ELM327 em centrais chinesas
                        val method = device.javaClass.getMethod(
                            "createRfcommSocket",
                            Int::class.javaPrimitiveType
                        )
                        val s = method.invoke(device, 1) as BluetoothSocket
                        s.connect()
                        s
                    }

                    val out = socket!!.outputStream
                    val inp = socket!!.inputStream

                    if (!initElm327(out, inp)) {
                        closeSocket()
                        postUpdate(ObdData(connected = false, statusText = "Sem resposta"))
                        Thread.sleep(4000)
                        continue
                    }

                    var lastSampleTime = System.currentTimeMillis()
                    var consecutiveErrors = 0

                    while (running.get() && socket?.isConnected == true) {
                        val volt = readVoltage(out, inp)
                        val temp = readCoolant(out, inp)
                        val rpm = readRpm(out, inp)
                        val speed = readSpeed(out, inp)
                        val load = readLoad(out, inp)
                        val maf = readMaf(out, inp)

                        if (volt == null && temp == null && rpm == null) {
                            consecutiveErrors++
                            if (consecutiveErrors >= 5) break
                        } else {
                            consecutiveErrors = 0
                        }

                        val now = System.currentTimeMillis()
                        val dtHours = ((now - lastSampleTime).coerceIn(50L, 5000L)) / 3600000.0
                        lastSampleTime = now

                        val afr = if (isEthanol) 9.0 else 13.2
                        val density = if (isEthanol) 810.0 else 745.0

                        var lph: Double? = null
                        var instKmL: Double? = null
                        if (maf != null) {
                            val fuelGramsPerSec = maf / afr
                            val calcLph = (fuelGramsPerSec * 3600.0) / density
                            lph = calcLph
                            if (speed != null) {
                                tripKm += speed * dtHours
                                tripLiters += calcLph * dtHours
                                if (speed > 2 && calcLph > 0.05) {
                                    instKmL = (speed / calcLph).coerceIn(0.0, 35.0)
                                }
                            }
                        }

                        val avg = if (tripLiters > 0.005) {
                            (tripKm / tripLiters).coerceIn(0.0, 35.0)
                        } else null

                        val gear = estimateTiida4AtGear(rpm, speed)

                        postUpdate(
                            ObdData(
                                connected = true,
                                statusText = "OBD Conectado",
                                coolantTempC = temp,
                                rpm = rpm,
                                speedKmh = speed,
                                loadPct = load,
                                instantKmL = instKmL,
                                avgKmL = avg,
                                litersPerHour = lph,
                                gear = gear,
                                voltage = volt
                            )
                        )
                        Thread.sleep(150)
                    }
                } catch (_: Exception) {
                    // Tenta reconectar em seguida se o carro ainda estiver ligado
                } finally {
                    closeSocket()
                }

                if (running.get()) {
                    postUpdate(ObdData(connected = false, statusText = "Reconectando..."))
                    try {
                        Thread.sleep(4000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            postUpdate(ObdData(connected = false, statusText = "OBD Off"))
        }.apply { start() }
    }

    fun disconnect() {
        running.set(false)
        closeSocket()
        workerThread?.interrupt()
        workerThread = null
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun postUpdate(data: ObdData) {
        mainHandler.post { onUpdate(data) }
    }

    private fun initElm327(out: OutputStream, inp: InputStream): Boolean {
        sendCommand(out, inp, "ATZ", 1200)
        Thread.sleep(500)
        sendCommand(out, inp, "ATE0")
        sendCommand(out, inp, "ATL0")
        sendCommand(out, inp, "ATS0")
        sendCommand(out, inp, "ATH0")
        val proto = sendCommand(out, inp, "ATSP0", 2000)
        return proto.isNotEmpty()
    }

    private fun sendCommand(
        out: OutputStream,
        inp: InputStream,
        cmd: String,
        timeoutMs: Long = 1500
    ): String {
        return try {
            while (inp.available() > 0) inp.read()
            out.write((cmd + "\r").toByteArray())
            out.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val b = inp.read()
                    if (b == -1) break
                    val c = b.toChar()
                    if (c == '>') break
                    sb.append(c)
                } else {
                    Thread.sleep(15)
                }
            }
            sb.toString()
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .uppercase()
        } catch (_: Exception) {
            ""
        }
    }

    private fun readVoltage(out: OutputStream, inp: InputStream): Double? {
        val res = sendCommand(out, inp, "ATRV")
        val match = Regex("(\\d+\\.\\d+)").find(res) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun readCoolant(out: OutputStream, inp: InputStream): Int? {
        val res = sendCommand(out, inp, "0105")
        val idx = res.indexOf("4105")
        if (idx == -1 || res.length < idx + 6) return null
        return res.substring(idx + 4, idx + 6).toIntOrNull(16)?.minus(40)
    }

    private fun readRpm(out: OutputStream, inp: InputStream): Int? {
        val res = sendCommand(out, inp, "010C")
        val idx = res.indexOf("410C")
        if (idx == -1 || res.length < idx + 8) return null
        val a = res.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return null
        val b = res.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return null
        return ((a * 256) + b) / 4
    }

    private fun readSpeed(out: OutputStream, inp: InputStream): Int? {
        val res = sendCommand(out, inp, "010D")
        val idx = res.indexOf("410D")
        if (idx == -1 || res.length < idx + 6) return null
        return res.substring(idx + 4, idx + 6).toIntOrNull(16)
    }

    private fun readLoad(out: OutputStream, inp: InputStream): Int? {
        val res = sendCommand(out, inp, "0104")
        val idx = res.indexOf("4104")
        if (idx == -1 || res.length < idx + 6) return null
        val a = res.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return null
        return (a * 100) / 255
    }

    private fun readMaf(out: OutputStream, inp: InputStream): Double? {
        val res = sendCommand(out, inp, "0110")
        val idx = res.indexOf("4110")
        if (idx == -1 || res.length < idx + 8) return null
        val a = res.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return null
        val b = res.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return null
        return ((a * 256) + b) / 100.0
    }

    private fun estimateTiida4AtGear(rpm: Int?, speedKmh: Int?): String {
        if (rpm == null || rpm < 300) return "--"
        if (speedKmh == null || speedKmh < 2) return "P/N"
        val ratio = rpm.toDouble() / speedKmh.toDouble()
        return when {
            ratio > 72.0 -> "1ª"
            ratio > 44.0 -> "2ª"
            ratio > 29.0 -> "3ª"
            else -> "4ª"
        }
    }
}

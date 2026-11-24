package org.voltarians.elmlab.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.voltarians.elmlab.*
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** User-started server with bounded clients, independent adapter sessions, and explicit shutdown. */
class EmulatorService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@EmulatorService }
    val simulation = SimulationState(VehicleProfile.presets().first())
    @Volatile var status = "Stopped"; private set
    @Volatile var bluetoothStatus = "Bluetooth off"; private set
    @Volatile var running = false; private set
    @Volatile private var generation = 0L
    private var tcpServer: ServerSocket? = null
    private var bluetoothServer: BluetoothServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Closeable>()
    private val workers = java.util.concurrent.ThreadPoolExecutor(8, 8, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(8))
    private val listeners = Executors.newFixedThreadPool(2)
    private val log = ArrayDeque<String>()
    private val recording = ArrayDeque<Pair<String, String>>()
    private var registration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("emulator", "Simulator connection", NotificationManager.IMPORTANCE_LOW))
    }
    @Synchronized override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSimulation(); return START_NOT_STICKY }
        if (!running) {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 0, Intent(this, EmulatorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "emulator").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ELM Lab simulator running").setContentText("TCP port 35000 • tap to open")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            startForeground(1, notification)
            running = true
            status = "Starting TCP…"
            val token = ++generation
            listeners.execute { startTcp(token) }
        }
        if (intent?.getBooleanExtra("bluetooth", false) == true) startBluetooth()
        return START_NOT_STICKY
    }

    private fun startTcp(token: Long) {
        var opened: ServerSocket? = null
        try {
            val socket = synchronized(this) {
                if (!running || token != generation) return
                ServerSocket(TCP_PORT).also { tcpServer = it; opened = it }
            }
            status = "TCP listening on $TCP_PORT"
            advertise()
            while (running && token == generation) {
                val client = socket.accept()
                client.soTimeout = 60_000
                client.tcpNoDelay = true
                accept(client, token) { handle(it.getInputStream(), it.getOutputStream(), "TCP", token) }
            }
        } catch (e: Exception) {
            if (running && token == generation && (tcpServer === opened || opened == null)) {
                status = "TCP failed: ${e.message}"
                record(status)
                stopSimulation()
            }
        }
    }

    private fun <T : Closeable> accept(client: T, token: Long, handle: (T) -> Unit) {
        synchronized(this) {
            if (!running || token != generation || clients.size >= 8) { client.close(); return }
            clients.add(client)
        }
        try {
            workers.execute {
                try { client.use { handle(it) } } catch (e: Exception) { if (running) record("Disconnected: ${e.javaClass.simpleName}") }
                finally { clients.remove(client) }
            }
        } catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun startBluetooth() {
        if (bluetoothServer != null || !running) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothStatus = "Bluetooth permission required"; return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            if (adapter == null || !adapter.isEnabled) { bluetoothStatus = "Turn on Bluetooth, then tap Enable Bluetooth"; return }
            val server = adapter.listenUsingRfcommWithServiceRecord("ELM Lab", SPP_UUID)
            bluetoothServer = server
            val token = generation
            bluetoothStatus = "Bluetooth listening (SPP)"
            listeners.execute {
                try {
                    while (running && token == generation && !Thread.currentThread().isInterrupted) {
                        val client = server.accept()
                        accept(client, token) { handle(it.inputStream, it.outputStream, "Bluetooth", token) }
                    }
                } catch (e: Exception) { if (running && bluetoothServer === server) { bluetoothStatus = "Bluetooth stopped: ${e.message}"; record(bluetoothStatus) } }
                finally { synchronized(this) { if (bluetoothServer === server) bluetoothServer = null }; runCatching { server.close() } }
            }
        } catch (e: Exception) { bluetoothStatus = "Bluetooth unavailable: ${e.message}"; record(bluetoothStatus) }
    }

    private fun handle(input: InputStream, output: OutputStream, transport: String, token: Long) {
        val engine = Elm327Engine(simulation)
        val command = StringBuilder()
        var lastWasCr = false
        record("$transport client connected")
        while (running && token == generation && !Thread.currentThread().isInterrupted) {
            val byte = input.read()
            if (byte < 0) break
            if (byte == 10 && lastWasCr) { lastWasCr = false; continue }
            lastWasCr = byte == 13
            if (byte == 13 || byte == 10) {
                val value = command.toString()
                command.clear()
                val delay = simulation.snapshot().responseDelayMs
                if (delay > 0) Thread.sleep(delay.toLong())
                val response = engine.execute(value)
                output.write(response.toByteArray(Charsets.US_ASCII)); output.flush()
                val normalized = value.replace(" ", "").uppercase(java.util.Locale.ROOT)
                if (normalized.matches(Regex("(?:[0-9A-F]{2}){1,32}"))) {
                    val clean = response.replace("\r\n", "\r").removePrefix("$normalized\r").removeSuffix("\r>")
                    synchronized(recording) { if (recording.size >= 500) recording.removeFirst(); recording.addLast(normalized to clean) }
                }
                record("$transport > $value\n< ${response.replace("\r", " ").replace("\n", " ")}")
            } else {
                if (byte !in 32..126 || command.length >= 512) return
                command.append(byte.toChar())
            }
        }
    }

    @Synchronized private fun advertise() {
        if (!running) return
        val manager = getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { record("Discovery unavailable: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(NsdServiceInfo().apply { serviceName = "ELM Lab"; serviceType = "_voltarian-elm._tcp."; port = TCP_PORT }, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { record("Discovery unavailable: ${it.message}") }
    }
    private fun record(value: String) = synchronized(log) {
        if (log.size >= 500) log.removeFirst()
        log.addLast("${System.currentTimeMillis()} $value")
    }
    fun transcript(): String = synchronized(log) { log.joinToString("\n") }
    fun recordedResponses(): Map<String, List<String>> = synchronized(recording) { recording.groupBy({ it.first }, { it.second }) }
    fun clearLog() { synchronized(log) { log.clear() }; synchronized(recording) { recording.clear() } }
    fun connectionCount(): Int = clients.size

    fun stopSimulation() {
        synchronized(this) {
            running = false
            generation++
            runCatching { tcpServer?.close() }; tcpServer = null
            runCatching { bluetoothServer?.close() }; bluetoothServer = null
            clients.forEach { runCatching { it.close() } }; clients.clear()
            registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }; registration = null
        }
        status = "Stopped"; bluetoothStatus = "Bluetooth off"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        stopSimulation()
        workers.shutdownNow(); listeners.shutdownNow()
        super.onDestroy()
    }
    companion object {
        const val TCP_PORT = 35000
        const val ACTION_STOP = "org.voltarians.elmlab.STOP"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

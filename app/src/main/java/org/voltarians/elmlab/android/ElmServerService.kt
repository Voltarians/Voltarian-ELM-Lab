package org.voltarians.elmlab.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.voltarians.elmlab.Elm327Engine
import org.voltarians.elmlab.ReliabilityTestStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class ElmServerService : Service() {
    private var tcpServer: ServerSocket? = null
    private var bluetoothServer: BluetoothServerSocket? = null
    private var bluetoothClient: BluetoothSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val engine = Elm327Engine()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting ELM Lab server…"))
        isRunning = true
        startTcpServer()
        advertiseNetworkService()
        startBluetoothServerIfPermitted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH_BLUETOOTH -> startBluetoothServerIfPermitted()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTcpServer() {
        if (tcpServer != null) return
        thread(name = "elm-tcp-server") {
            try {
                val socket = ServerSocket(TCP_PORT).also { tcpServer = it }
                updateNotification("TCP listening on $TCP_PORT")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(name = "elm-tcp-client") {
                        client.use { handleClient(it.getInputStream(), it.getOutputStream(), "TCP") }
                    }
                }
            } catch (_: Exception) {
                if (tcpServer != null) updateNotification("TCP server error — restart ELM Lab")
            }
        }
    }

    private fun startBluetoothServerIfPermitted() {
        if (bluetoothServer != null ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        ) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) return
        thread(name = "elm-bluetooth-server") {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                bluetoothServer = server
                while (bluetoothServer != null) {
                    val socket = server.accept()
                    bluetoothClient = socket
                    try {
                        handleClient(socket.inputStream, socket.outputStream, "Bluetooth")
                    } finally {
                        socket.close()
                        bluetoothClient = null
                    }
                }
            } catch (_: Exception) {
                bluetoothServer = null
            }
        }
    }

    private fun handleClient(input: InputStream, output: OutputStream, transport: String) {
        val command = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            if (byte.toChar() == '\r' || byte.toChar() == '\n') {
                if (command.isNotEmpty()) {
                    val value = command.toString()
                    if (value.trim().replace(" ", "").equals("ATMA", ignoreCase = true)) {
                        runReliabilityTestStream(output, transport)
                    } else {
                        val response = engine.execute(value)
                        output.write(response.toByteArray())
                        output.flush()
                    }
                    command.clear()
                }
            } else command.append(byte.toChar())
        }
    }

    private fun runReliabilityTestStream(output: OutputStream, transport: String) {
        updateNotification(
            "TRANS$TCP_PORT reliability stream: ${ReliabilityTestStream.expectedFrames} frames",
        )
        val startedNanos = System.nanoTime()
        for (sequence in 0 until ReliabilityTestStream.expectedFrames) {
            val targetNanos = startedNanos + sequence * ReliabilityTestStream.intervalNanos
            while (true) {
                val remainingNanos = targetNanos - System.nanoTime()
                if (remainingNanos <= 0) break
                Thread.sleep(remainingNanos / 1_000_000, (remainingNanos % 1_000_000).toInt())
            }
            output.write("${ReliabilityTestStream.frame(sequence)}\r".toByteArray())
            if (sequence % ReliabilityTestStream.framesPerSecond == 0) output.flush()
        }
        output.write(">".toByteArray())
        output.flush()
        updateNotification("TCP listening on $TCP_PORT")
    }

    private fun advertiseNetworkService() {
        if (registrationListener != null) return
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        manager.registerService(
            NsdServiceInfo().apply {
                serviceName = "Voltarian ELM Lab"
                serviceType = "_voltarian-elm._tcp."
                port = TCP_PORT
            },
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ELM Lab server", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("Voltarian ELM Lab is running")
        .setContentText(message)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        runCatching { bluetoothClient?.close() }
        runCatching { bluetoothServer?.close() }
        runCatching { tcpServer?.close() }
        bluetoothClient = null
        bluetoothServer = null
        tcpServer = null
        registrationListener?.let { listener ->
            runCatching {
                (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(listener)
            }
        }
        registrationListener = null
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.voltarians.elmlab.START"
        const val ACTION_STOP = "org.voltarians.elmlab.STOP"
        const val ACTION_REFRESH_BLUETOOTH = "org.voltarians.elmlab.REFRESH_BLUETOOTH"
        const val TCP_PORT = 35000
        private const val CHANNEL_ID = "elm_server"
        private const val NOTIFICATION_ID = 35000
        private const val SERVICE_NAME = "Voltarian ELM327"
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        @Volatile var isRunning: Boolean = false
            private set
    }
}

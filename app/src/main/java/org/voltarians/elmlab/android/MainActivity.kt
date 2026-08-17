package org.voltarians.elmlab.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.voltarians.elmlab.Elm327Engine
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var tcpServer: ServerSocket? = null
    private var bluetoothServer: BluetoothServerSocket? = null
    private var bluetoothClient: BluetoothSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val engine = Elm327Engine()
    private lateinit var status: TextView
    private lateinit var details: TextView

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startBluetoothServer()
            requestDiscoverable()
        } else {
            updateStatus("TCP listening on 35000", "Bluetooth permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Stopped"; textSize = 20f }
        details = TextView(this).apply { text = "TCP: port 35000\nBluetooth: ELM327-compatible SPP" }
        val button = Button(this).apply { text = "Start ELM emulator" }
        button.setOnClickListener {
            if (tcpServer == null) {
                startTcpServer()
                advertiseNetworkService()
                enableBluetooth()
                button.text = "Stop emulator"
            } else {
                stopAll()
                updateStatus("Stopped", "TCP and Bluetooth inactive")
                button.text = "Start ELM emulator"
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "Voltarian ELM Lab"; textSize = 28f })
            addView(TextView(context).apply { text = "ELM327 test adapter over Wi-Fi and Bluetooth Classic" })
            addView(status)
            addView(details)
            addView(button)
        })
    }

    private fun startTcpServer() {
        thread(name = "elm-tcp-server") {
            try {
                val socket = ServerSocket(TCP_PORT).also { tcpServer = it }
                updateStatus("TCP listening on $TCP_PORT", "Starting Bluetooth…")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(name = "elm-tcp-client") {
                        client.use { handleClient(it.getInputStream(), it.getOutputStream(), "TCP") }
                    }
                }
            } catch (_: Exception) {
                if (tcpServer != null) updateStatus("TCP server error", "Restart the emulator")
            }
        }
    }

    private fun enableBluetooth() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            updateStatus("TCP listening on $TCP_PORT", "Bluetooth is not available")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            if (required.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                bluetoothPermissions.launch(required)
                return
            }
        }
        startBluetoothServer()
        requestDiscoverable()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("TCP listening on $TCP_PORT", "Turn on Bluetooth, then restart the emulator")
            return
        }
        thread(name = "elm-bluetooth-server") {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                bluetoothServer = server
                updateStatus("TCP and Bluetooth listening", "Pair with this phone, then connect using ELM327 SPP")
                while (bluetoothServer != null) {
                    val socket = server.accept()
                    bluetoothClient = socket
                    updateStatus("Bluetooth client connected", socket.remoteDevice.name ?: socket.remoteDevice.address)
                    try {
                        handleClient(socket.inputStream, socket.outputStream, "Bluetooth")
                    } finally {
                        socket.close()
                        bluetoothClient = null
                        if (bluetoothServer != null) updateStatus("TCP and Bluetooth listening", "Bluetooth client disconnected")
                    }
                }
            } catch (_: Exception) {
                if (bluetoothServer != null) updateStatus("TCP listening on $TCP_PORT", "Bluetooth server stopped")
            } finally {
                bluetoothServer = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverable() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            })
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
                    val response = engine.execute(value)
                    output.write(response.toByteArray())
                    output.flush()
                    updateDetails("$transport: $value → ${response.lineSequence().firstOrNull().orEmpty()}")
                    command.clear()
                }
            } else command.append(byte.toChar())
        }
    }

    private fun advertiseNetworkService() {
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        manager.registerService(NsdServiceInfo().apply {
            serviceName = "Voltarian ELM Lab"
            serviceType = "_voltarian-elm._tcp."
            port = TCP_PORT
        }, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopAll() {
        runCatching { bluetoothClient?.close() }
        runCatching { bluetoothServer?.close() }
        bluetoothClient = null
        bluetoothServer = null
        runCatching { tcpServer?.close() }
        tcpServer = null
        registrationListener?.let { listener ->
            runCatching { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(listener) }
        }
        registrationListener = null
    }

    private fun updateStatus(title: String, subtitle: String) = runOnUiThread {
        status.text = title
        details.text = subtitle
    }

    private fun updateDetails(value: String) = runOnUiThread { details.text = value }

    override fun onDestroy() { stopAll(); super.onDestroy() }

    companion object {
        private const val TCP_PORT = 35000
        private const val SERVICE_NAME = "Voltarian ELM327"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

package org.voltarians.elmlab.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var button: Button

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasBluetoothConnectPermission()) {
            startService(
                Intent(this, ElmServerService::class.java)
                    .setAction(ElmServerService.ACTION_REFRESH_BLUETOOTH),
            )
            requestDiscoverable()
        }
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 20f }
        details = TextView(this)
        button = Button(this).apply {
            setOnClickListener {
                if (ElmServerService.isRunning) {
                    startService(
                        Intent(this@MainActivity, ElmServerService::class.java)
                            .setAction(ElmServerService.ACTION_STOP),
                    )
                } else {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, ElmServerService::class.java)
                            .setAction(ElmServerService.ACTION_START),
                    )
                    requestRuntimePermissions()
                }
                postDelayed({ updateUi() }, 250)
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(context).apply {
                    text = "Voltarian ELM Lab"
                    textSize = 28f
                })
                addView(TextView(context).apply {
                    text = "Persistent ELM327 test adapter over Wi-Fi and Bluetooth Classic"
                })
                addView(status)
                addView(details)
                addView(button)
            },
        )
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val running = ElmServerService.isRunning
        status.text = if (running) "ELM service running" else "Stopped"
        details.text = if (running) {
            "TCP: 127.0.0.1:${ElmServerService.TCP_PORT}\n" +
                "The foreground service remains active while Voltarian is open."
        } else {
            "TCP and Bluetooth inactive"
        }
        button.text = if (running) "Stop emulator" else "Start ELM emulator"
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val required = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (required.isNotEmpty()) {
            permissions.launch(required.toTypedArray())
        } else {
            requestDiscoverable()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestDiscoverable() {
        if (!hasBluetoothConnectPermission()) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                },
            )
        }
    }
}

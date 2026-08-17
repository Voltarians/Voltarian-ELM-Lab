package org.voltarians.elmlab.android

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.voltarians.elmlab.Elm327Engine
import java.net.ServerSocket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var server: ServerSocket? = null
    private val engine = Elm327Engine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Stopped"; textSize = 20f }
        val button = Button(this).apply { text = "Start TCP emulator" }
        button.setOnClickListener {
            if (server == null) {
                startServer(status)
                button.text = "Stop emulator"
            } else {
                stopServer()
                status.text = "Stopped"
                button.text = "Start TCP emulator"
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "Voltarian ELM Lab"; textSize = 28f })
            addView(TextView(context).apply { text = "ELM327-compatible TCP test adapter on port 35000" })
            addView(status)
            addView(button)
        })
    }

    private fun startServer(status: TextView) {
        thread(name = "elm-tcp-server") {
            try {
                val socket = ServerSocket(35000).also { server = it }
                runOnUiThread { status.text = "Listening on port 35000" }
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(name = "elm-client") {
                        client.use {
                            val input = it.getInputStream()
                            val output = it.getOutputStream()
                            val command = StringBuilder()
                            while (true) {
                                val byte = input.read()
                                if (byte < 0) break
                                if (byte.toChar() == '\r' || byte.toChar() == '\n') {
                                    if (command.isNotEmpty()) {
                                        output.write(engine.execute(command.toString()).toByteArray())
                                        output.flush()
                                        command.clear()
                                    }
                                } else command.append(byte.toChar())
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { if (server != null) status.text = "Server error" }
            }
        }
    }

    private fun stopServer() { server?.close(); server = null }
    override fun onDestroy() { stopServer(); super.onDestroy() }
}


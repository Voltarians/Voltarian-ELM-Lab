package org.voltarians.elmlab

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Loopback-only development harness. Not packaged in the Android app. */
fun main(args: Array<String>) {
    val server = ServerSocket(args.firstOrNull()?.toInt() ?: 0, 8, InetAddress.getLoopbackAddress())
    val state = SimulationState(VehicleProfile.presets().first { it.name == "Engine fault" }.copy(rpm = 2200, speedKph = 100, coolantC = 90))
    val pool = Executors.newFixedThreadPool(8)
    Runtime.getRuntime().addShutdownHook(Thread { server.close(); pool.shutdownNow() })
    println("PORT=${server.localPort}")
    System.out.flush()
    while (!server.isClosed) {
        val client = try { server.accept() } catch (_: Exception) { break }
        client.soTimeout = 30_000
        pool.execute {
            runCatching {
                client.use { socket -> ElmStreamSession(Elm327Engine(state)).serve(socket.getInputStream(), socket.getOutputStream()) }
            }
        }
    }
}

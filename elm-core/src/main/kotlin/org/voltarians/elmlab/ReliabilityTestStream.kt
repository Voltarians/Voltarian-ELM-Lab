package org.voltarians.elmlab

/** Deterministic CAN stream used to qualify logger frame loss and file integrity. */
object ReliabilityTestStream {
    const val canId = "7E8"
    const val framesPerSecond = 100
    const val durationSeconds = 30
    const val expectedFrames = framesPerSecond * durationSeconds
    const val intervalNanos = 1_000_000_000L / framesPerSecond

    fun frame(sequence: Int): String {
        require(sequence in 0 until expectedFrames)
        val counter = sequence.toLong() and 0xffff_ffffL
        return "%s %02X %02X %02X %02X 56 45 4C 4D".format(
            canId,
            counter ushr 24,
            counter ushr 16 and 0xff,
            counter ushr 8 and 0xff,
            counter and 0xff,
        )
    }
}

package com.jerrymk.kigeyes

/** RTSP has no moving live timeline in Media3; bound receiver lag without server seeks. */
internal object WearerLatencyPolicy {
    const val MIN_BUFFER_MS=100
    const val MAX_BUFFER_MS=500
    const val START_BUFFER_MS=80
    const val REBUFFER_MS=100

    fun speed(bufferedMs: Long, playing: Boolean): Float = when {
        !playing -> 1f
        bufferedMs > 1500 -> 2f
        bufferedMs > 600 -> 1.5f
        bufferedMs > 300 -> 1.15f
        bufferedMs > 180 -> 1.04f
        else -> 1f
    }
}

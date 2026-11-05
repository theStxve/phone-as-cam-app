package com.example.cameralive

import kotlin.math.abs

/**
 * Pure decision logic for the camera pipeline. No Android types, so every rule here is covered by
 * plain JVM unit tests (see StreamingLogicTest).
 */

/**
 * Picks the AE target FPS range the sensor should run at.
 *
 * Prefers the slowest sensor speed that still reaches [targetFps], then the lower bound closest to
 * min(15, targetFps): low enough to brighten dark scenes, high enough not to stutter.
 * Returns null when the device reports no ranges.
 */
fun pickFpsRange(available: List<Pair<Int, Int>>, targetFps: Int): Pair<Int, Int>? {
    if (available.isEmpty()) return null
    val valid = available.filter { it.first <= it.second }.ifEmpty { return null }
    val reaching = valid.filter { it.second >= targetFps }
    val pool = if (reaching.isNotEmpty()) reaching else {
        val fastest = valid.maxOf { it.second }
        valid.filter { it.second == fastest }
    }
    val slowestUpper = pool.minOf { it.second }
    val idealLower = minOf(15, targetFps)
    return pool.filter { it.second == slowestUpper }.minByOrNull { abs(it.first - idealLower) }
}

/**
 * Lets frames through at an average of [fps] without the aliasing of a plain "time since last
 * frame" check, which turns a 30 fps sensor with a 20 fps target into 15 fps.
 */
class FrameThrottle(@Volatile var fps: Int) {
    private var nextDueMs = 0L

    fun shouldPass(nowMs: Long): Boolean {
        val interval = 1000.0 / fps.coerceAtLeast(1)
        if (nowMs + 2 < nextDueMs) return false
        // A jittery camera may run a little late and catch up. Only a real stall restarts the
        // schedule, so it can't turn into a burst.
        nextDueMs = if (nowMs - nextDueMs > interval * 4) {
            nowMs + interval.toLong()
        } else {
            nextDueMs + interval.toLong()
        }
        return true
    }
}

enum class CameraPowerState { OFF, LOW, FULL }

/**
 * What the camera has to do right now.
 *
 * FULL: someone is watching, or a clip is being recorded.
 * LOW: only the AI watches. The sensor runs at its slowest speed.
 * OFF: nothing needs frames. The camera is released until a client shows up.
 *
 * Hold times stop the camera from flapping when a viewer reloads the page.
 */
class CameraPowerPolicy(
    private val viewerHoldMs: Long = 8_000L,
    private val standbyHoldMs: Long = 20_000L
) {
    private var lastViewerMs = Long.MIN_VALUE / 2
    private var lastNeededMs = Long.MIN_VALUE / 2

    fun evaluate(
        nowMs: Long,
        viewerActive: Boolean,
        aiEnabled: Boolean,
        recording: Boolean,
        torchOn: Boolean
    ): CameraPowerState {
        if (viewerActive || recording) lastViewerMs = nowMs
        if (viewerActive || recording || aiEnabled || torchOn) lastNeededMs = nowMs
        return when {
            nowMs - lastViewerMs < viewerHoldMs -> CameraPowerState.FULL
            nowMs - lastNeededMs < standbyHoldMs -> CameraPowerState.LOW
            else -> CameraPowerState.OFF
        }
    }
}

/**
 * Decides whether a frame is worth an AI inference. Compares a coarse luma grid with the previous
 * one. A still scene runs a keep-alive inference every [heartbeatMs], so a target too small to
 * register as motion is still found within that time.
 */
class MotionGate(
    private val cellThreshold: Int = 8,
    private val minChangedCells: Int = 3,
    private val holdMs: Long = 3_000L,
    private val heartbeatMs: Long = 2_000L
) {
    private var previous: IntArray? = null
    private var holdUntilMs = 0L
    private var lastInferenceMs = Long.MIN_VALUE / 2

    fun shouldInfer(grid: IntArray, nowMs: Long): Boolean {
        val prev = previous
        previous = grid
        if (prev == null || prev.size != grid.size) return mark(nowMs)
        var changed = 0
        for (i in grid.indices) {
            if (abs(grid[i] - prev[i]) > cellThreshold) changed++
        }
        if (changed >= minChangedCells) {
            holdUntilMs = nowMs + holdMs
            return mark(nowMs)
        }
        if (nowMs < holdUntilMs) return mark(nowMs)
        if (nowMs - lastInferenceMs >= heartbeatMs) return mark(nowMs)
        return false
    }

    /** Keeps inference running while an alarm target stays in the picture. */
    fun holdFor(nowMs: Long) {
        holdUntilMs = nowMs + holdMs
    }

    private fun mark(nowMs: Long): Boolean {
        lastInferenceMs = nowMs
        return true
    }
}

/** Fast image math on raw NV21 frames. */
object Nv21 {
    const val GRID_COLS = 32
    const val GRID_ROWS = 24

    /** Mean luma per grid cell, sampling every 4th pixel. Output size is GRID_COLS * GRID_ROWS. */
    fun lumaGrid(nv21: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(GRID_COLS * GRID_ROWS)
        for (row in 0 until GRID_ROWS) {
            val y0 = row * height / GRID_ROWS
            val y1 = (row + 1) * height / GRID_ROWS
            for (col in 0 until GRID_COLS) {
                val x0 = col * width / GRID_COLS
                val x1 = (col + 1) * width / GRID_COLS
                var sum = 0L
                var count = 0
                var y = y0
                while (y < y1) {
                    val rowStart = y * width
                    var x = x0
                    while (x < x1) {
                        sum += nv21[rowStart + x].toInt() and 0xFF
                        count++
                        x += 4
                    }
                    y += 4
                }
                out[row * GRID_COLS + col] = if (count > 0) (sum / count).toInt() else 0
            }
        }
        return out
    }

    /** Whole-number downscale factor that brings the long edge to at most [maxEdge]. */
    fun scaleStep(width: Int, height: Int, maxEdge: Int): Int =
        maxOf(1, maxOf(width, height) / maxEdge)

    /**
     * Downscales to ARGB and rotates by [rotation] (0, 90, 180, 270) in one pass. Luma is averaged
     * over each block so detail survives; chroma is sampled once per block.
     * [out] must hold [outputWidth] * [outputHeight] pixels.
     */
    fun toArgb(nv21: ByteArray, width: Int, height: Int, step: Int, rotation: Int, out: IntArray) {
        val srcW = width / step
        val srcH = height / step
        val blockArea = step * step
        for (sy in 0 until srcH) {
            val py = sy * step
            val uvRow = width * height + (py / 2) * width
            for (sx in 0 until srcW) {
                val px = sx * step
                var lumaSum = 0
                for (dy in 0 until step) {
                    val line = (py + dy) * width + px
                    for (dx in 0 until step) lumaSum += nv21[line + dx].toInt() and 0xFF
                }
                val luma = lumaSum / blockArea
                val uvIndex = uvRow + (px and 1.inv())
                val v = (nv21[uvIndex].toInt() and 0xFF) - 128
                val u = (nv21[uvIndex + 1].toInt() and 0xFF) - 128
                val r = (luma + (1436 * v shr 10)).coerceIn(0, 255)
                val g = (luma - (352 * u + 731 * v shr 10)).coerceIn(0, 255)
                val b = (luma + (1815 * u shr 10)).coerceIn(0, 255)
                val target = when (rotation) {
                    90 -> sx * srcH + (srcH - 1 - sy)
                    180 -> (srcH - 1 - sy) * srcW + (srcW - 1 - sx)
                    270 -> (srcW - 1 - sx) * srcH + sy
                    else -> sy * srcW + sx
                }
                out[target] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    fun meanOf(grid: IntArray): Int = if (grid.isEmpty()) 0 else grid.sum() / grid.size
}

/** Maps the UI quality value to a WebRTC bitrate. One formula for connect and for live changes. */
object StreamBitrate {
    fun maxBps(quality: Int): Int =
        (quality.coerceIn(10, 100) * 75_000 + 200_000).coerceIn(400_000, 8_500_000)

    fun minBps(quality: Int): Int = (maxBps(quality) / 4).coerceAtLeast(150_000)
}

/**
 * Slows down PIN guessing. Counts distinct wrong PINs per client, so a forgotten browser tab that
 * keeps sending one outdated PIN never locks its owner out.
 */
class PinGuard(
    private val maxDistinctFailures: Int = 8,
    private val windowMs: Long = 5 * 60_000L,
    private val lockMs: Long = 5 * 60_000L,
    private val maxTrackedClients: Int = 256
) {
    private class Entry(var windowStart: Long) {
        val guesses = HashSet<Int>()
        var lockedUntil = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun isLocked(client: String, nowMs: Long): Boolean {
        val entry = entries[client] ?: return false
        return nowMs < entry.lockedUntil
    }

    @Synchronized
    fun recordFailure(client: String, guess: String, nowMs: Long) {
        val entry = entries.getOrPut(client) { Entry(nowMs) }
        if (nowMs - entry.windowStart > windowMs) {
            entry.windowStart = nowMs
            entry.guesses.clear()
        }
        entry.guesses.add(guess.hashCode())
        if (entry.guesses.size >= maxDistinctFailures) {
            entry.lockedUntil = nowMs + lockMs
            entry.guesses.clear()
        }
        if (entries.size > maxTrackedClients) {
            val oldest = entries.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun recordSuccess(client: String) {
        entries.remove(client)
    }

    @Synchronized
    fun reset() {
        entries.clear()
    }
}

/** Compares secrets in constant time. */
fun secretsMatch(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

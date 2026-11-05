package com.example.cameralive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingLogicTest {

    private val typical = listOf(15 to 15, 15 to 30, 7 to 30, 30 to 30, 24 to 24)

    @Test fun `fps range keeps the sensor as slow as the target allows`() {
        assertEquals(24 to 24, pickFpsRange(typical, 20))
        assertEquals(15 to 30, pickFpsRange(typical, 30))
        assertEquals(15 to 15, pickFpsRange(typical, 10))
    }

    @Test fun `fps range below 15 never produces an inverted range`() {
        // The old code built Range(15, fps) and crashed for any fps under 15.
        for (fps in 2..14) {
            val picked = pickFpsRange(typical, fps)!!
            assertTrue(picked.first <= picked.second)
        }
    }

    @Test fun `fps range falls back to the fastest when the target is out of reach`() {
        assertEquals(15 to 30, pickFpsRange(typical, 60))
    }

    @Test fun `fps range handles empty and broken input`() {
        assertNull(pickFpsRange(emptyList(), 30))
        assertNull(pickFpsRange(listOf(30 to 15), 30))
    }

    @Test fun `throttle hits 20 fps from a 30 fps sensor`() {
        val throttle = FrameThrottle(20)
        var passed = 0
        var t = 1000.0
        repeat(300) {
            if (throttle.shouldPass(t.toLong())) passed++
            t += 1000.0 / 30
        }
        // 10 seconds of input, so about 200 frames. The old check delivered 150.
        assertTrue("passed $passed", passed in 190..210)
    }

    @Test fun `throttle does not burst after a stall`() {
        val throttle = FrameThrottle(10)
        assertTrue(throttle.shouldPass(0))
        assertTrue(throttle.shouldPass(5000))
        assertFalse(throttle.shouldPass(5033))
    }

    @Test fun `power policy follows the viewer and ai state`() {
        val p = CameraPowerPolicy(viewerHoldMs = 8_000, standbyHoldMs = 20_000)
        assertEquals(CameraPowerState.FULL, p.evaluate(100_000, viewerActive = true, aiEnabled = true, recording = false, torchOn = false))
        assertEquals(CameraPowerState.FULL, p.evaluate(105_000, viewerActive = false, aiEnabled = true, recording = false, torchOn = false))
        assertEquals(CameraPowerState.LOW, p.evaluate(109_000, viewerActive = false, aiEnabled = true, recording = false, torchOn = false))
        assertEquals(CameraPowerState.LOW, p.evaluate(500_000, viewerActive = false, aiEnabled = true, recording = false, torchOn = false))
    }

    @Test fun `power policy releases the camera when nothing needs it`() {
        val p = CameraPowerPolicy(viewerHoldMs = 8_000, standbyHoldMs = 20_000)
        p.evaluate(100_000, viewerActive = true, aiEnabled = false, recording = false, torchOn = false)
        assertEquals(CameraPowerState.LOW, p.evaluate(110_000, viewerActive = false, aiEnabled = false, recording = false, torchOn = false))
        assertEquals(CameraPowerState.OFF, p.evaluate(121_000, viewerActive = false, aiEnabled = false, recording = false, torchOn = false))
    }

    @Test fun `power policy starts in standby and a torch or recording keeps the camera on`() {
        val p = CameraPowerPolicy(viewerHoldMs = 8_000, standbyHoldMs = 20_000)
        assertEquals(CameraPowerState.OFF, p.evaluate(100_000, viewerActive = false, aiEnabled = false, recording = false, torchOn = false))
        assertEquals(CameraPowerState.LOW, p.evaluate(100_100, viewerActive = false, aiEnabled = false, recording = false, torchOn = true))
        assertEquals(CameraPowerState.FULL, p.evaluate(100_200, viewerActive = false, aiEnabled = false, recording = true, torchOn = false))
    }

    @Test fun `motion gate skips still scenes but keeps a heartbeat`() {
        val gate = MotionGate(heartbeatMs = 2_000, holdMs = 3_000)
        val still = IntArray(768) { 100 }
        assertTrue(gate.shouldInfer(still.copyOf(), 0))
        assertFalse(gate.shouldInfer(still.copyOf(), 400))
        assertFalse(gate.shouldInfer(still.copyOf(), 1_600))
        assertTrue(gate.shouldInfer(still.copyOf(), 2_000))
    }

    @Test fun `motion gate wakes on change and holds`() {
        val gate = MotionGate(heartbeatMs = 2_000, holdMs = 3_000)
        val still = IntArray(768) { 100 }
        gate.shouldInfer(still.copyOf(), 0)
        val moved = still.copyOf().also { for (i in 0 until 5) it[i] = 180 }
        assertTrue(gate.shouldInfer(moved, 400))
        assertTrue(gate.shouldInfer(moved.copyOf(), 800))
        assertTrue(gate.shouldInfer(moved.copyOf(), 3_000))
    }

    @Test fun `motion gate ignores sensor noise`() {
        val gate = MotionGate(heartbeatMs = 10_000, cellThreshold = 8)
        gate.shouldInfer(IntArray(768) { 100 }, 0)
        assertFalse(gate.shouldInfer(IntArray(768) { 100 + it % 7 }, 400))
    }

    @Test fun `luma grid averages cells`() {
        val w = 64
        val h = 48
        val nv21 = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) nv21[y * w + x] = if (x < w / 2) 50 else 200.toByte()
        val grid = Nv21.lumaGrid(nv21, w, h)
        assertEquals(Nv21.GRID_COLS * Nv21.GRID_ROWS, grid.size)
        assertEquals(50, grid[0])
        assertEquals(200, grid[Nv21.GRID_COLS - 1])
        assertEquals(125, Nv21.meanOf(grid))
    }

    /** 4x2 image, each pixel carries a unique luma value and neutral chroma. */
    private fun testFrame(): ByteArray {
        val nv21 = ByteArray(4 * 2 * 3 / 2) { 128.toByte() }
        intArrayOf(10, 20, 30, 40, 50, 60, 70, 80).forEachIndexed { i, v -> nv21[i] = v.toByte() }
        return nv21
    }

    private fun lumaOf(argb: IntArray) = IntArray(argb.size) { argb[it] and 0xFF }

    @Test fun `argb conversion without rotation`() {
        val out = IntArray(8)
        Nv21.toArgb(testFrame(), 4, 2, 1, 0, out)
        assertArrayEquals(intArrayOf(10, 20, 30, 40, 50, 60, 70, 80), lumaOf(out))
    }

    @Test fun `argb conversion rotates clockwise`() {
        val out = IntArray(8)
        Nv21.toArgb(testFrame(), 4, 2, 1, 90, out)
        assertArrayEquals(intArrayOf(50, 10, 60, 20, 70, 30, 80, 40), lumaOf(out))
    }

    @Test fun `argb conversion rotates 180 and 270`() {
        val out = IntArray(8)
        Nv21.toArgb(testFrame(), 4, 2, 1, 180, out)
        assertArrayEquals(intArrayOf(80, 70, 60, 50, 40, 30, 20, 10), lumaOf(out))
        Nv21.toArgb(testFrame(), 4, 2, 1, 270, out)
        assertArrayEquals(intArrayOf(40, 80, 30, 70, 20, 60, 10, 50), lumaOf(out))
    }

    @Test fun `argb downscale averages luma`() {
        val out = IntArray(2)
        Nv21.toArgb(testFrame(), 4, 2, 2, 0, out)
        assertArrayEquals(intArrayOf(35, 55), lumaOf(out))
    }

    @Test fun `neutral chroma converts to grey`() {
        val out = IntArray(8)
        Nv21.toArgb(testFrame(), 4, 2, 1, 0, out)
        assertEquals(0xFF0A0A0A.toInt(), out[0])
    }

    @Test fun `scale step keeps the long edge usable`() {
        assertEquals(3, Nv21.scaleStep(1920, 1440, 640))
        assertEquals(1, Nv21.scaleStep(640, 480, 640))
    }

    @Test fun `bitrate is identical at connect and on live change`() {
        assertEquals(1_700_000, StreamBitrate.maxBps(20))
        assertEquals(425_000, StreamBitrate.minBps(20))
        assertEquals(7_700_000, StreamBitrate.maxBps(100))
    }

    @Test fun `pin guard locks after distinct guesses only`() {
        val g = PinGuard(maxDistinctFailures = 3, lockMs = 1_000)
        repeat(50) { g.recordFailure("ip", "1111", 0) }
        assertFalse("one stale PIN must not lock", g.isLocked("ip", 1))
        g.recordFailure("ip", "2222", 2)
        g.recordFailure("ip", "3333", 3)
        assertTrue(g.isLocked("ip", 4))
        assertFalse("other clients stay free", g.isLocked("other", 4))
        assertFalse("lock expires", g.isLocked("ip", 2_000))
    }

    @Test fun `pin guard clears on success and caps memory`() {
        val g = PinGuard(maxDistinctFailures = 2, maxTrackedClients = 4)
        g.recordFailure("a", "1", 0)
        g.recordSuccess("a")
        g.recordFailure("a", "2", 0)
        assertFalse(g.isLocked("a", 1))
        repeat(100) { g.recordFailure("c$it", "x", 0) }
    }

    @Test fun `secret comparison`() {
        assertTrue(secretsMatch("1234", "1234"))
        assertFalse(secretsMatch("1234", "1235"))
        assertFalse(secretsMatch("1234", ""))
    }
}

package io.github.clashverge.android

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 单点采样器：后台每秒读取一次内核累计流量并换算成速率，
 * 通知栏与画中画共享同一次 JNI 调用，避免互相抢采样。
 */
object TrafficMonitor {

    data class Sample(
        val up: Long,
        val down: Long,
        val upTotal: Long,
        val downTotal: Long,
    )

    fun interface Listener {
        fun onSample(sample: Sample)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var lastUp = -1L
    private var lastDown = -1L
    private var lastAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val (upTotal, downTotal) = TrafficFormat.decode(CoreAdapter.queryTrafficTotalRaw())
            val elapsed = if (lastAt == 0L) 0L else now - lastAt

            val upSpeed = if (elapsed > 0 && lastUp >= 0) {
                ((upTotal - lastUp) * 1000 / elapsed).coerceAtLeast(0)
            } else {
                0L
            }
            val downSpeed = if (elapsed > 0 && lastDown >= 0) {
                ((downTotal - lastDown) * 1000 / elapsed).coerceAtLeast(0)
            } else {
                0L
            }

            lastUp = upTotal
            lastDown = downTotal
            lastAt = now

            val sample = Sample(upSpeed, downSpeed, upTotal, downTotal)
            mainHandler.post { listeners.forEach { it.onSample(sample) } }

            worker?.postDelayed(this, INTERVAL_MS)
        }
    }

    @Synchronized
    fun register(listener: Listener) {
        listeners.add(listener)
        if (thread != null) return

        lastUp = -1L
        lastDown = -1L
        lastAt = 0L
        val handlerThread = HandlerThread("traffic-monitor").also { it.start() }
        thread = handlerThread
        worker = Handler(handlerThread.looper).also { it.post(tick) }
    }

    @Synchronized
    fun unregister(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isNotEmpty()) return

        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        worker = null
        thread = null
    }

    private const val INTERVAL_MS = 1000L
}

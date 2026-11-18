package io.github.clashverge.android

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 画中画窗口内容：复刻首页左下角的流量统计块
 * （走势图 + 上行/下行速率 + 内存占用）。
 */
class PipTrafficView(context: Context) : LinearLayout(context) {

    private val chart = TrafficChartView(context)
    private val upValue = createValueText(UP_COLOR)
    private val downValue = createValueText(DOWN_COLOR)
    private val memoryValue = createValueText(MEMORY_COLOR)
    private var memoryTicks = 0

    private val listener = TrafficMonitor.Listener { sample ->
        upValue.text = TrafficFormat.speed(sample.up)
        downValue.text = TrafficFormat.speed(sample.down)
        chart.append(sample.up, sample.down)
        if (memoryTicks++ % 3 == 0) updateMemory()
    }

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0xFF181A1B.toInt())
            setStroke(dp(1), 0x24FFFFFF)
        }

        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(createSpeedRow())
        addView(createMemoryRow())
        updateMemory()
    }

    fun start() {
        chart.clear()
        memoryTicks = 0
        updateMemory()
        TrafficMonitor.register(listener)
    }

    fun stop() {
        TrafficMonitor.unregister(listener)
    }

    private fun createSpeedRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(30))
        }
        row.addView(
            createSpeedItem("↑", UP_COLOR, upValue),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        row.addView(
            createSpeedItem("↓", DOWN_COLOR, downValue),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        return row
    }

    private fun createSpeedItem(label: String, color: Int, value: TextView): View {
        val item = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(dp(20), LayoutParams.MATCH_PARENT)
        }
        value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        item.addView(labelView)
        item.addView(value, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return item
    }

    private fun createMemoryRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(20))
        }
        val labelView = TextView(context).apply {
            text = "内存"
            setTextColor(MEMORY_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LayoutParams(dp(36), LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        memoryValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        row.addView(labelView)
        row.addView(memoryValue, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    private fun createValueText(color: Int): TextView = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.END
        text = "0 B/s"
        maxLines = 1
    }

    private fun updateMemory() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val info = manager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() ?: return
        memoryValue.text = TrafficFormat.size(info.totalPss.toLong() * 1024)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private class TrafficChartView(context: Context) : View(context) {

        private val upSeries = ArrayDeque<Float>()
        private val downSeries = ArrayDeque<Float>()

        private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UP_COLOR
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DOWN_COLOR
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun append(up: Long, down: Long) {
            upSeries.addLast(up.toFloat())
            downSeries.addLast(down.toFloat())
            while (upSeries.size > MAX_SAMPLES) upSeries.removeFirst()
            while (downSeries.size > MAX_SAMPLES) downSeries.removeFirst()
            invalidate()
        }

        fun clear() {
            upSeries.clear()
            downSeries.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (upSeries.size < 2 || width == 0 || height == 0) return

            var max = 1f
            upSeries.forEach { max = maxOf(max, it) }
            downSeries.forEach { max = maxOf(max, it) }

            drawSeries(canvas, upSeries, max, upPaint)
            drawSeries(canvas, downSeries, max, downPaint)
        }

        private fun drawSeries(
            canvas: Canvas,
            series: ArrayDeque<Float>,
            max: Float,
            paint: Paint,
        ) {
            val contentWidth = width.toFloat()
            val top = dp(4f)
            val bottom = height.toFloat() - dp(4f)
            val usable = bottom - top
            val step = contentWidth / (MAX_SAMPLES - 1)
            val startX = contentWidth - (series.size - 1) * step

            path.reset()
            series.forEachIndexed { index, value ->
                val x = startX + index * step
                val y = bottom - (value / max) * usable
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        private fun dp(value: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

        companion object {
            private const val MAX_SAMPLES = 60
        }
    }

    companion object {
        private const val UP_COLOR = 0xFFFFA726.toInt()
        private const val DOWN_COLOR = 0xFF42A5F5.toInt()
        private const val MEMORY_COLOR = 0xFF9E9E9E.toInt()
    }
}

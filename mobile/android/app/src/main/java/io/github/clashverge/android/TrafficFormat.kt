package io.github.clashverge.android

import java.util.Locale

/**
 * mihomo JNI 把上行/下行流量分別压缩进一个 Long 的高/低 32 位，
 * 每 32 位内部又用高 2 位表示单位倍率（B/KB/MB/GB）。
 */
object TrafficFormat {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun decode(traffic: Long): Pair<Long, Long> {
        return scale(traffic ushr 32) to scale(traffic and 0xFFFFFFFFL)
    }

    private fun scale(value: Long): Long {
        val type = ((value ushr 30) and 0x3L).toInt()
        val data = value and 0x3FFFFFFFL
        return when (type) {
            0 -> data
            1 -> data shl 10
            2 -> data shl 20
            else -> data shl 30
        }
    }

    fun size(bytes: Long): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        // 与前端 parse-traffic 一致：最多 3 位有效数字
        val number = if (unit == 0 || value >= 1000) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.3g", value)
        }
        return "$number ${UNITS[unit]}"
    }

    fun speed(bytesPerSecond: Long): String = "${size(bytesPerSecond)}/s"
}

package io.github.clashverge.android

import android.content.Context
import org.json.JSONObject

/**
 * Verge 配置适配器。
 *
 * 使用 SharedPreferences 存储 CVR 的 Verge 配置（主题、端口、TUN 开关等）。
 * 后续可映射到 CMFA 的 ServiceStore。
 */
object VergeAdapter {

    private const val PREFS_NAME = "clashverge_verge"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val defaults = mapOf(
        "language" to "zh",
        "theme_mode" to "dark",
        "enable_tun_mode" to false,
        "enable_system_proxy" to false,
        "mixed_port" to 7897,
        "enable_external_controller" to true,
        "external_controller_port" to 9090,
        "external_controller_secret" to "",
        "log_level" to "info",
        "enable_builtin_enhanced" to false,
        "enable_auto_launch" to false,
        "enable_silent_start" to false,
        "enable_system_proxy_auto_bypass" to false,
        "proxy_auto_launch" to false,
        "enable_tray" to false,
        "enable_global_dark_mode" to true,
        "menu_font_size" to 13,
        "theme_blur_radius" to 8,
        "auto_close_connection" to true,
        "default_latency_test" to "http://www.gstatic.com/generate_204",
        "default_latency_timeout" to 5000,
        "enable_random_select" to false,
        "merge_same_ssid" to true,
    )

    fun getConfig(): String {
        val config = JSONObject()
        for ((key, value) in defaults) {
            when (value) {
                is String -> config.put(key, prefs().getString(key, value) ?: value)
                is Boolean -> config.put(key, prefs().getBoolean(key, value))
                is Int -> config.put(key, prefs().getInt(key, value))
                is Long -> config.put(key, prefs().getLong(key, value))
            }
        }
        return config.toString()
    }

    fun patchConfig(patch: JSONObject?) {
        if (patch == null) return
        val editor = prefs().edit()
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = patch.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.apply()

        // 如果 TUN 模式或代理配置变化，通知核心重启
        if (patch.has("enable_tun_mode") || patch.has("mixed_port")) {
            // TODO: 通知 service 重启
        }
    }
}

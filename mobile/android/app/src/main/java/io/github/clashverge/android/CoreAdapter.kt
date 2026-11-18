package io.github.clashverge.android

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对接 CMFA core 模块（libclash.so + JNI）的适配器。
 *
 * CMFA 的 core 模块通过 JNI 暴露 mihomo 内核能力，
 * 入口为 com.github.kr328.clash.core.Clash 单例。
 */
object CoreAdapter {

    private var controllerAddress = "127.0.0.1:9090"
    private var controllerSecret = ""

    fun setControllerInfo(address: String, secret: String) {
        controllerAddress = address
        controllerSecret = secret
    }

    /**
     * 控制器由 mihomo 配置中的 external-controller 启动（见 ClashVergeService.injectControllerConfig）。
     * 这里不能调用 Clash.startHttp —— 它启动的是 HTTP 代理监听，会与控制器端口冲突。
     */
    fun ensureHttpStarted(): String = controllerAddress

    fun getVersion(): String {
        return try {
            val state = Clash.queryTunnelState()
            "mihomo-${state.mode.name.lowercase()}"
        } catch (e: Throwable) {
            "mihomo-android"
        }
    }

    fun getControllerAddress(): String = ensureHttpStarted()

    fun getSecret(): String = controllerSecret

    fun getMode(): String {
        return try {
            Clash.queryTunnelState().mode.name.lowercase()
        } catch (e: Throwable) {
            "rule"
        }
    }

    fun setMode(mode: String) {
        try {
            val override = Clash.queryOverride(Clash.OverrideSlot.Persist)
            override.mode = modeOf(mode)
            Clash.patchOverride(Clash.OverrideSlot.Persist, override)
        } catch (e: Throwable) {
            android.util.Log.e("CoreAdapter", "setMode failed", e)
        }
    }

    fun patchConfigs(payload: JSONObject) {
        try {
            // writeOverride 会整体覆盖，必须先读出已有配置再合并，避免丢失控制器等设置
            val override = Clash.queryOverride(Clash.OverrideSlot.Persist)
            if (payload.has("mixed-port")) override.mixedPort = payload.optInt("mixed-port")
            if (payload.has("port")) override.httpPort = payload.optInt("port")
            if (payload.has("socks-port")) override.socksPort = payload.optInt("socks-port")
            if (payload.has("allow-lan")) override.allowLan = payload.optBoolean("allow-lan")
            if (payload.has("mode")) override.mode = modeOf(payload.optString("mode"))
            if (payload.has("log-level")) override.logLevel = levelOf(payload.optString("log-level"))
            if (payload.has("ipv6")) override.ipv6 = payload.optBoolean("ipv6")
            if (payload.has("unified-delay")) override.unifiedDelay = payload.optBoolean("unified-delay")
            if (payload.has("tcp-concurrent")) override.tcpConcurrent = payload.optBoolean("tcp-concurrent")
            if (payload.has("external-controller")) {
                override.externalController = payload.optString("external-controller")
            }
            Clash.patchOverride(Clash.OverrideSlot.Persist, override)
        } catch (e: Throwable) {
            android.util.Log.e("CoreAdapter", "patchConfigs failed", e)
        }
    }

    private fun levelOf(level: String): com.github.kr328.clash.core.model.LogMessage.Level =
        when (level.lowercase()) {
            "silent" -> com.github.kr328.clash.core.model.LogMessage.Level.Silent
            "debug" -> com.github.kr328.clash.core.model.LogMessage.Level.Debug
            "warning", "warn" -> com.github.kr328.clash.core.model.LogMessage.Level.Warning
            "error" -> com.github.kr328.clash.core.model.LogMessage.Level.Error
            else -> com.github.kr328.clash.core.model.LogMessage.Level.Info
        }

    private fun modeOf(mode: String): TunnelState.Mode = when (mode.lowercase()) {
        "global" -> TunnelState.Mode.Global
        "direct" -> TunnelState.Mode.Direct
        "script" -> TunnelState.Mode.Script
        else -> TunnelState.Mode.Rule
    }

    fun getConfigs(): String {
        // UiConfiguration is empty; configs are fetched via HTTP controller by the frontend
        return "{}"
    }

    fun getConfigsYaml(): String {
        return ""
    }

    /**
     * 构造前端 ProxyViewV1 结构（schemaVersion = 1）。
     * 前端 get_proxy_view 会校验 schemaVersion，旧结构会导致代理页整体报错。
     */
    fun getProxyView(): String {
        return try {
            val names = Clash.queryGroupNames(false)
            val groupNames = names.toSet()
            val records = JSONObject()
            val groups = JSONArray()
            var global: JSONObject? = null
            var directRecordId: String? = null

            fun recordIdOf(name: String) = "core:$name"

            fun ensureRecord(name: String, type: String, delay: Int) {
                val recordId = recordIdOf(name)
                if (records.has(recordId)) return
                records.put(recordId, JSONObject().apply {
                    put("recordId", recordId)
                    put("name", name)
                    put("type", type)
                    put("history", if (delay > 0) JSONArray().apply {
                        put(JSONObject().apply {
                            put("time", nowIso())
                            put("delay", delay)
                        })
                    } else JSONArray())
                    put("source", JSONObject().apply {
                        put("kind", "core")
                        put("proxyName", name)
                    })
                    putCapabilities(this)
                })
                if (name == "DIRECT") directRecordId = recordId
            }

            for (name in names) {
                val group = runCatching { Clash.queryGroup(name, ProxySort.Default) }.getOrNull()
                    ?: continue
                val members = JSONArray()
                for (proxy in group.proxies) {
                    if (groupNames.contains(proxy.name)) {
                        members.put(JSONObject().apply {
                            put("kind", "group")
                            put("name", proxy.name)
                        })
                    } else {
                        ensureRecord(proxy.name, proxy.type, proxy.delay)
                        members.put(JSONObject().apply {
                            put("kind", "node")
                            put("name", proxy.name)
                            put("recordId", recordIdOf(proxy.name))
                        })
                    }
                }
                val view = JSONObject().apply {
                    put("name", name)
                    put("type", group.type)
                    put("now", group.now)
                    put("history", JSONArray())
                    put("members", members)
                    putCapabilities(this)
                }
                if (name == "GLOBAL") global = view else groups.put(view)
            }

            JSONObject().apply {
                put("schemaVersion", 1)
                put("orderSource", "runtime")
                put("providerState", "ready")
                put("global", global ?: JSONObject.NULL)
                put("direct", directRecordId ?: JSONObject.NULL)
                put("groups", groups)
                put("records", records)
                put("standalone", JSONArray())
                put("providers", JSONArray())
            }.toString()
        } catch (e: Throwable) {
            android.util.Log.e("CoreAdapter", "getProxyView failed", e)
            JSONObject().apply {
                put("schemaVersion", 1)
                put("orderSource", "fallback")
                put("providerState", "unavailable")
                put("global", JSONObject.NULL)
                put("direct", JSONObject.NULL)
                put("groups", JSONArray())
                put("records", JSONObject())
                put("standalone", JSONArray())
                put("providers", JSONArray())
            }.toString()
        }
    }

    private fun putCapabilities(target: JSONObject) {
        target.put("udp", false)
        target.put("xudp", false)
        target.put("tfo", false)
        target.put("mptcp", false)
        target.put("smux", false)
    }

    private fun nowIso(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date())
    }

    fun selectProxy(group: String, node: String) {
        try {
            Clash.patchSelector(group, node)
        } catch (e: Throwable) {
            // ignore
        }
    }

    fun testProxyDelay(name: String, url: String): String {
        return try {
            runBlocking {
                Clash.healthCheck(name).await()
            }
            JSONObject().apply { put("delay", 0) }.toString()
        } catch (e: Throwable) {
            "{}"
        }
    }

    fun isRunning(): Boolean {
        return try {
            Clash.queryTunnelState().mode != TunnelState.Mode.Direct
        } catch (e: Throwable) {
            false
        }
    }

    fun restart(context: Context) {
        try {
            Clash.reset()
        } catch (e: Throwable) {
            // ignore
        }
    }

    fun startHttp(listenAt: String): String? {
        return try {
            Clash.startHttp(listenAt)
        } catch (e: Throwable) {
            null
        }
    }

    fun stopHttp() {
        try {
            Clash.stopHttp()
        } catch (e: Throwable) {
            // ignore
        }
    }

    fun queryTrafficNow(): String {
        return try {
            val traffic = Clash.queryTrafficNow()
            JSONObject().apply {
                put("up", traffic.trafficUpload())
                put("down", traffic.trafficDownload())
            }.toString()
        } catch (e: Throwable) {
            "{}"
        }
    }

    fun queryTrafficTotal(): String {
        return try {
            val traffic = Clash.queryTrafficTotal()
            JSONObject().apply {
                put("up", traffic.trafficUpload())
                put("down", traffic.trafficDownload())
            }.toString()
        } catch (e: Throwable) {
            "{}"
        }
    }

    /**
     * 原始累计流量（编码后的 Long），用于通知栏和画中画计算速率。
     */
    fun queryTrafficTotalRaw(): Long {
        return try {
            Clash.queryTrafficTotal()
        } catch (e: Throwable) {
            0L
        }
    }
}

package io.github.clashverge.android

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 暴露给 WebView 的 JS 接口，将前端 Tauri invoke 调用路由到原生层。
 *
 * 命令分三类：
 * 1. plugin:mihomo|*  → 通过 HTTP 调用 mihomo external controller（localhost）
 * 2. app 命令（profiles/verge/clash 等）→ 委托给 CMFA service 模块
 * 3. 桌面专属命令（tray/window/autostart 等）→ 返回 stub
 */
class ClashBridge(
    private val activity: MainActivity,
    private val webView: WebView,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mihomoHttp = MihomoHttpClient()

    @Volatile
    private var logForwarding = false
    private var logJob: kotlinx.coroutines.Job? = null

    // callback id 分配
    private var callbackSeq = 0L
    private val callbackOnceMap = HashMap<Long, Boolean>()

    @JavascriptInterface
    fun invoke(cmd: String, argsJson: String, callbackId: String) {
        scope.launch {
            val result = runCatching { handle(cmd, argsJson) }
                .getOrElse { e ->
                    JSONObject().apply {
                        put("__error", true)
                        put("detail", e.message ?: "unknown error")
                    }.toString()
                }
            postToWebView("javascript:window.__tauriCallback($callbackId, '${escapeJs(result)}')")
        }
    }

    @JavascriptInterface
    fun allocateCallbackId(once: Boolean): Long {
        val id = ++callbackSeq
        callbackOnceMap[id] = once
        return id
    }

    // ===== 窗口标题栏按钮（最小化 / 画中画 / 关闭） =====

    @JavascriptInterface
    fun windowAction(action: String) {
        activity.runOnUiThread {
            when (action) {
                "minimize" -> activity.minimizeToHome()
                "pip" -> activity.enterPipMode()
                "close" -> activity.closeApp()
            }
        }
    }

    // ===== 剪贴板 =====
    @JavascriptInterface
    fun readClipboard(): String {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        return if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString() ?: ""
        } else {
            ""
        }
    }

    @JavascriptInterface
    fun writeClipboard(text: String) {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
    }

    // ===== 打开外部链接 =====
    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(cmd: String, argsJson: String): String {
        val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        return when {
            cmd.startsWith("plugin:mihomo|") -> handleMihomo(cmd.removePrefix("plugin:mihomo|"), args)
            else -> handleAppCommand(cmd, args)
        }
    }

    // ===== mihomo external controller 命令 =====
    private fun handleMihomo(command: String, args: JSONObject): String {
        val base = mihomoHttp.baseUrl()
        return when (command) {
            "http" -> {
                // 通用 HTTP 代理：前端通过 invoke 走原生层，避免 WebView CORS 限制
                val method = args.optString("method", "GET").uppercase()
                val path = args.optString("path", "/")
                val body = args.optJSONObject("body")
                val result = when (method) {
                    "GET" -> mihomoHttp.get("$base$path")
                    "POST" -> mihomoHttp.post("$base$path", body ?: JSONObject())
                    "PUT" -> mihomoHttp.put("$base$path", body ?: JSONObject())
                    "PATCH" -> mihomoHttp.patch("$base$path", body ?: JSONObject())
                    "DELETE" -> mihomoHttp.delete("$base$path")
                    else -> "null"
                }
                // 前端 invoke 会对返回值先 JSON.parse 一次，这里必须返回 JSON 字符串字面量；
                // 错误对象保持原样，交由 invoke 抛出。
                if (result.startsWith("{\"__error\":true")) result else JSONObject.quote(result)
            }
            "update_controller" -> {
                mihomoHttp.setController(args.optString("host"), args.optInt("port"))
                "null"
            }
            "update_secret" -> {
                mihomoHttp.setSecret(args.optString("secret"))
                "null"
            }
            "stream_logs" -> {
                if (args.optBoolean("enabled", true)) startLogStream() else stopLogStream()
                "null"
            }
            "get_version" -> mihomoHttp.get("$base/version")
            "get_base_config" -> mihomoHttp.get("$base/configs")
            "patch_base_config" -> mihomoHttp.patch("$base/configs", args.optJSONObject("payload") ?: JSONObject())
            "reload_config" -> mihomoHttp.put("$base/configs?force=${args.optBoolean("force", false)}", JSONObject())
            "get_proxies" -> mihomoHttp.get("$base/proxies")
            "get_proxy_by_name" -> mihomoHttp.get("$base/proxies/${args.optString("name")}")
            "select_node_for_group" -> {
                val group = args.optString("group_name")
                val node = args.optString("node_name")
                mihomoHttp.put("$base/proxies/$group", JSONObject().put("name", node))
            }
            "unfixed_proxy" -> {
                val group = args.optString("group_name")
                mihomoHttp.put("$base/proxies/$group", JSONObject().put("name", "DIRECT"))
            }
            "delay_proxy_by_name" -> {
                val name = args.optString("name")
                val url = args.optString("url", "http://www.gstatic.com/generate_204")
                val timeout = args.optInt("timeout", 5000)
                mihomoHttp.get("$base/proxies/$name/delay?url=${java.net.URLEncoder.encode(url, "UTF-8")}&timeout=$timeout")
            }
            "get_groups" -> mihomoHttp.get("$base/group")
            "get_group_by_name" -> mihomoHttp.get("$base/group/${args.optString("name")}")
            "delay_group" -> {
                val name = args.optString("name")
                val url = args.optString("url", "http://www.gstatic.com/generate_204")
                val timeout = args.optInt("timeout", 5000)
                mihomoHttp.get("$base/group/$name/delay?url=${java.net.URLEncoder.encode(url, "UTF-8")}&timeout=$timeout")
            }
            "get_rules" -> mihomoHttp.get("$base/rules")
            "get_rule_providers" -> mihomoHttp.get("$base/providers/rules")
            "update_rule_provider" -> mihomoHttp.put("$base/providers/rules/${args.optString("name")}", JSONObject())
            "get_proxy_providers" -> mihomoHttp.get("$base/providers/proxies")
            "get_proxy_provider_by_name" -> mihomoHttp.get("$base/providers/proxies/${args.optString("name")}")
            "update_proxy_provider" -> mihomoHttp.put("$base/providers/proxies/${args.optString("name")}", JSONObject())
            "healthcheck_proxy_provider" -> mihomoHttp.get("$base/providers/proxies/${args.optString("name")}/healthcheck")
            "healthcheck_node_in_provider" -> mihomoHttp.get("$base/providers/proxies/${args.optString("provider")}/${args.optString("node")}/healthcheck")
            "get_connections" -> mihomoHttp.get("$base/connections")
            "close_all_connections" -> mihomoHttp.delete("$base/connections")
            "close_connection" -> mihomoHttp.delete("$base/connections/${args.optString("connectionId")}")
            "flush_fakeip" -> mihomoHttp.post("$base/cache/fakeip/flush", JSONObject())
            "flush_dns" -> mihomoHttp.post("$base/cache/dns/flush", JSONObject())
            "update_geo" -> mihomoHttp.post("$base/configs/geo", JSONObject())
            "restart" -> {
                // 重启内核由 CMFA service 处理
                restartCore()
                "null"
            }
            "upgrade_core" -> "null" // Android 端内核随 APK 发布，禁用在线升级
            "upgrade_ui" -> "null"
            "upgrade_geo" -> "null"
            "clear_all_ws_connections" -> "null"
            // WebSocket 连接命令：前端直接使用 ws:// 连接 mihomo，此处返回 null 即可
            else -> "null"
        }
    }

    /**
     * mihomo 的 REST /logs 在 CMFA 内核下没有推送，改用 JNI 日志订阅转发到 WebView。
     */
    private fun startLogStream() {
        logForwarding = true
        if (logJob != null) return
        logJob = scope.launch {
            try {
                for (msg in com.github.kr328.clash.core.Clash.subscribeLogcat()) {
                    if (!logForwarding) continue
                    val json = JSONObject().apply {
                        put("type", msg.level.name.lowercase())
                        put("payload", msg.message)
                    }.toString()
                    postToWebView("javascript:window.__mihomoLogPush&&window.__mihomoLogPush(${JSONObject.quote(json)})")
                }
            } catch (e: Throwable) {
                android.util.Log.e("ClashBridge", "log stream failed", e)
            } finally {
                logJob = null
            }
        }
    }

    private fun stopLogStream() {
        logForwarding = false
    }

    // ===== 应用级命令：委托给 CMFA service =====
    private fun handleAppCommand(cmd: String, args: JSONObject): String {
        return when (cmd) {
            // WebView 内 fetch 受 CORS 限制，外部 HTTP 请求统一由原生代发
            "http_fetch" -> {
                val headers = mutableMapOf<String, String>()
                args.optJSONObject("headers")?.let { obj ->
                    obj.keys().forEach { key -> headers[key] = obj.optString(key) }
                }
                val (status, text) = mihomoHttp.fetchExternal(
                    method = args.optString("method", "GET"),
                    url = args.optString("url"),
                    headers = headers,
                    body = args.optString("body").takeIf { it.isNotBlank() },
                )
                JSONObject().apply {
                    put("status", status)
                    put("ok", status in 200..299)
                    put("body", text)
                }.toString()
            }
            // —— 配置 / Clash ——
            "get_clash_info" -> ClashVergeService.getClashInfo()
            "patch_clash_config" -> ClashVergeService.patchClashConfig(activity, args.optJSONObject("payload"))
            "patch_clash_mode" -> ClashVergeService.patchClashMode(activity, args.optString("payload"))
            "get_clash_mode" -> ClashVergeService.getClashMode()
            // 运行中配置必须取控制器实时值（前端据此显示混合端口等），
            // CoreAdapter.getConfigs() 只有静态占位数据。
            "get_runtime_config" -> mihomoHttp.get("${mihomoHttp.baseUrl()}/configs")
            "get_runtime_yaml" -> ClashVergeService.getRuntimeYaml()
            "change_clash_core" -> ClashVergeService.changeClashCore(args.optString("core"))
            "restart_core" -> { restartCore(); "null" }

            // —— 代理 ——
            "get_proxy_view" -> ClashVergeService.getProxyView()
            "switch_proxy_node" -> ClashVergeService.switchProxyNode(
                args.optString("groupName"),
                args.optString("proxyName"),
            )
            "record_selected_node" -> ClashVergeService.recordSelectedNode(
                args.optString("groupName"),
                args.optString("node"),
            )
            "get_runtime_proxy_chain_config" -> ClashVergeService.getProxyChainConfig()
            "update_proxy_chain_config_in_runtime" -> ClashVergeService.updateProxyChainConfig(
                args.optJSONObject("proxyChainConfig"),
            )
            "test_delay" -> ClashVergeService.testDelay(args.optString("url"))

            // —— 订阅 / Profile ——
            "get_profiles" -> ClashVergeService.getProfiles()
            "create_profile" -> ClashVergeService.createProfile(args.optJSONObject("item"), args.optString("fileData"))
            "patch_profile" -> ClashVergeService.patchProfile(args.optString("index"), args.optJSONObject("profile"))
            "delete_profile" -> ClashVergeService.deleteProfile(args.optString("index"))
            "reorder_profile" -> ClashVergeService.reorderProfile(
                args.optString("activeId"),
                args.optString("overId"),
            )
            "update_profile" -> ClashVergeService.updateProfile(activity, args.optString("index"))
            "get_next_update_time" -> ProfileAdapter.nextUpdateTime(args.optString("uid")).toString()
            "view_profile" -> ClashVergeService.viewProfile(args.optString("index"))
            "read_profile_file" -> ClashVergeService.readProfileFile(args.optString("index"))
            "save_profile_file" -> ClashVergeService.saveProfileFile(args.optString("index"), args.optString("fileData"))
            "import_profile" -> ClashVergeService.importProfile(activity, args.optString("url"), args.optJSONObject("option"))
            "patch_profiles_config" -> ClashVergeService.patchProfilesConfig(activity, args.optJSONObject("profiles"))
            "enhance_profiles" -> ClashVergeService.enhanceProfiles()

            // —— Verge 配置 ——
            "get_verge_config" -> ClashVergeService.getVergeConfig()
            // 前端 bindings 传的是 payload（早期实现误读 patch，导致所有设置开关失效）
            "patch_verge_config" -> ClashVergeService.patchVergeConfig(
                activity,
                args.optJSONObject("payload") ?: args.optJSONObject("patch"),
            )

            // —— 系统 / 网络 ——
            "get_system_info" -> ClashVergeService.getSystemInfo(activity)
            "get_app_uptime" -> ClashVergeService.getAppUptime()
            "get_network_interfaces" -> ClashVergeService.getNetworkInterfaces()
            "get_network_interfaces_info" -> ClashVergeService.getNetworkInterfacesInfo()
            "get_system_hostname" -> ClashVergeService.getSystemHostname()
            "get_sys_proxy" -> "null" // Android 无系统代理概念
            "get_auto_proxy" -> "null"
            "save_proxy_ports" -> ClashVergeService.saveProxyPorts(args.optJSONObject("settings"))
            "probe_listener" -> ClashVergeService.probeListener(args.optJSONObject("request"))
            "get_embedded_server_port" -> ClashVergeService.getEmbeddedServerPort()

            // —— 运行时状态 ——
            "get_runtime_state" -> ClashVergeService.getRuntimeState()
            "get_pending_failures" -> "[]"
            "get_sidecar_failure" -> "null"
            "get_core_startup_error" -> "null"
            "take_service_fallback_notice" -> "null"
            "take_service_repair_notice" -> "false"
            "take_service_owner_notice" -> "null"
            "take_discarded_keys_notice" -> "null"

            // —— 日志 ——
            "get_clash_logs" -> ClashVergeService.getClashLogs()
            "get_runtime_logs" -> ClashVergeService.getRuntimeLogs()

            // —— DNS ——
            "get_dns_config_content" -> ClashVergeService.getDnsConfigContent()
            "save_dns_config" -> ClashVergeService.saveDnsConfig(args.optJSONObject("dnsConfig")?.toString().orEmpty())
            "apply_dns_config" -> ClashVergeService.applyDnsConfig()
            "validate_dns_config" -> ClashVergeService.validateDnsConfig()
            "set_dns_override" -> ClashVergeService.setDnsOverride(args.optString("profileUid"), args.optBoolean("enabled"))
            "take_dns_override_notice" -> "false"

            // —— 备份 ——
            "create_local_backup" -> ClashVergeService.createLocalBackup()
            "list_local_backup" -> ClashVergeService.listLocalBackup()
            "delete_local_backup" -> ClashVergeService.deleteLocalBackup(args.optString("filename"))
            "restore_local_backup" -> ClashVergeService.restoreLocalBackup(args.optString("filename"))
            "import_local_backup" -> ClashVergeService.importLocalBackup(args.optString("source"))
            "export_local_backup" -> ClashVergeService.exportLocalBackup(args.optString("filename"))
            "create_webdav_backup" -> ClashVergeService.createWebdavBackup()
            "save_webdav_config" -> ClashVergeService.saveWebdavConfig(args.optString("url"), args.optString("username"), args.optString("password"))
            "list_webdav_backup" -> ClashVergeService.listWebdavBackup()
            "delete_webdav_backup" -> ClashVergeService.deleteWebdavBackup(args.optString("filename"))
            "restore_webdav_backup" -> ClashVergeService.restoreWebdavBackup(args.optString("filename"))

            // —— 媒体解锁 ——
            "get_unlock_items" -> ClashVergeService.getUnlockItems()
            "check_media_unlock" -> ClashVergeService.checkMediaUnlock()
            "check_media_unlock_item" -> ClashVergeService.checkMediaUnlockItem(args.optString("name"))

            // —— 图标 ——
            "copy_icon_file" -> "null"
            "download_icon_cache" -> "null"

            // —— 桌面专属命令 stub ——
            "open_app_dir" -> "null"
            "open_logs_dir" -> "null"
            "open_core_dir" -> "null"
            "get_app_dir" -> "null"
            "open_devtools" -> "null"
            "restart_app" -> "null"
            "exit_app" -> "null"
            "copy_clash_env" -> "null"
            "invoke_uwp_tool" -> "null"
            "entry_lightweight_mode" -> "null"
            "install_service" -> "null"
            "uninstall_service" -> "null"
            "reinstall_service" -> "null"
            "repair_service" -> "null"
            "continue_with_sidecar" -> "null"
            "install_update" -> "null"
            "cancel_update_download" -> "null"
            "upgrade_clash_core" -> "null"
            "sync_runtime_providers" -> "null"
            "export_diagnostic_info" -> "null"

            else -> "null"
        }
    }

    private fun restartCore() {
        ClashVergeService.restartCore(activity)
    }

    private fun postToWebView(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    fun release() {
        scope.cancel()
        mihomoHttp.close()
    }
}

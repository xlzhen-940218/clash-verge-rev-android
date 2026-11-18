package io.github.clashverge.android

import android.content.Context
import android.os.Build
import com.github.kr328.clash.core.Clash
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 适配层：将 Clash Verge Rev 的命令语义转换为 CMFA service 模块的 API 调用。
 *
 * CMFA 的 service 模块提供：
 *  - ProfileManager：订阅/配置文件管理
 *  - ServiceStore：持久化设置
 *  - TunService：VpnService 实现
 *  - 内核控制（通过 core 模块的 JNI 桥接 libclash.so）
 *
 * 本类封装了这些 API，使 ClashBridge 无需直接依赖 CMFA 的内部实现细节。
 * 具体的 CMFA API 调用需要根据 CMFA 实际的类签名填充。
 */
object ClashVergeService {

    private const val TAG = "ClashVergeService"
    private const val CONTROLLER_ADDRESS = "127.0.0.1:9090"
    private const val UNLOCK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val LOAD_TIMEOUT_MS = 180_000L

    private val loadLock = Any()
    private var coreLoaded = false

    // 首页“运行时间”显示的是应用进程存活时长，不是设备开机时长
    private val processStartedAt = android.os.SystemClock.elapsedRealtime()

    // ===== 内核 / Clash 信息 =====

    fun getClashInfo(): String {
        // CMFA: 通过 core 模块获取内核版本、external-controller 地址、secret 等
        // 示例：val info = Core.instance.getClashInfo()
        val info = JSONObject().apply {
            put("version", CoreAdapter.getVersion())
            put("external-controller", CoreAdapter.getControllerAddress())
            put("secret", CoreAdapter.getSecret())
            put("mode", CoreAdapter.getMode())
        }
        return info.toString()
    }

    fun patchClashConfig(context: Context, payload: JSONObject?): String {
        if (payload == null) return "null"
        CoreAdapter.patchConfigs(payload)
        reloadCurrent(context)
        return "null"
    }

    fun patchClashMode(context: Context, mode: String): String {
        CoreAdapter.setMode(mode)
        reloadCurrent(context)
        return "null"
    }

    /**
     * Override 只写入文件，运行中的内核不会感知，必须重新加载当前配置才会生效。
     */
    private fun reloadCurrent(context: Context) {
        val uid = ProfileAdapter.getCurrentProfile()
        if (uid.isEmpty()) return
        try {
            loadProfileConfig(context, uid)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to reload profile config: $uid", e)
        }
    }

    fun getClashMode(): String {
        // 前端 getClashMode 期望返回字符串（"rule" | "global" | "direct"），不是 JSON 对象
        return JSONObject.quote(CoreAdapter.getMode())
    }

    fun getRuntimeConfig(): String {
        // 返回当前生效的完整配置（YAML 转 JSON）
        return CoreAdapter.getConfigs()
    }

    fun getRuntimeYaml(): String {
        return CoreAdapter.getConfigsYaml()
    }

    fun changeClashCore(core: String): String {
        // Android 端内核随 APK 发布，切换内核需重启 service
        return "null"
    }

    // ===== 代理 =====

    fun getProxyView(): String {
        // 聚合 mihomo /proxies 和 /group 数据，返回 CVR 期望的 ProxyView 结构
        return CoreAdapter.getProxyView()
    }

    fun switchProxyNode(group: String, node: String): String {
        CoreAdapter.selectProxy(group, node)
        return "null"
    }

    fun recordSelectedNode(group: String, node: String): String {
        CoreAdapter.selectProxy(group, node)
        return "null"
    }

    fun getProxyChainConfig(): String = "{}"

    fun updateProxyChainConfig(config: JSONObject?): String = "null"

    fun testDelay(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return "0"
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            val start = System.currentTimeMillis()
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 10_000) }
            (System.currentTimeMillis() - start).toString()
        } catch (e: Exception) {
            "0"
        }
    }

    // ===== 订阅 / Profile =====

    fun getProfiles(): String {
        return ProfileAdapter.getProfiles()
    }

    fun createProfile(item: JSONObject?, fileData: String?): String {
        return ProfileAdapter.createProfile(item, fileData)
    }

    fun patchProfile(index: String, patch: JSONObject?): String {
        return ProfileAdapter.patchProfile(index, patch)
    }

    fun deleteProfile(index: String): String {
        ProfileAdapter.deleteProfile(index)
        return "null"
    }

    fun reorderProfile(activeId: String, overId: String): String {
        ProfileAdapter.reorderProfile(activeId, overId)
        return "null"
    }

    fun updateProfile(context: Context, index: String): String {
        ProfileAdapter.updateProfile(index)
        // 订阅内容变化后运行中的内核不会自动感知，必须重新加载才会生效
        if (index == ProfileAdapter.getCurrentProfile()) {
            patchProfilesConfig(context, JSONObject().put("current", index))
        }
        return "null"
    }

    fun viewProfile(index: String): String {
        return ProfileAdapter.viewProfile(index)
    }

    fun readProfileFile(index: String): String {
        return ProfileAdapter.readProfileFile(index)
    }

    fun saveProfileFile(index: String, fileData: String): String {
        ProfileAdapter.saveProfileFile(index, fileData)
        return JSONObject().apply { put("status", "valid") }.toString()
    }

    fun importProfile(context: Context, url: String, option: JSONObject?): String {
        val result = ProfileAdapter.importProfile(url, option)
        // 首次导入时自动启用，否则代理/连接/规则页面拿不到任何配置
        val uid = runCatching { JSONObject(result).optString("uid") }.getOrNull()
        if (!uid.isNullOrEmpty() && !ProfileAdapter.hasCurrentProfile()) {
            patchProfilesConfig(context, JSONObject().put("current", uid))
        }
        return result
    }

    fun patchProfilesConfig(context: Context, profiles: JSONObject?): String {
        val uid = profiles?.optString("current").orEmpty()
        ProfileAdapter.setCurrentProfile(uid)
        if (uid.isEmpty()) {
            return JSONObject().apply { put("status", "valid") }.toString()
        }
        return try {
            loadProfileConfig(context, uid)
            coreLoaded = true
            JSONObject().apply { put("status", "valid") }.toString()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load profile config: $uid", e)
            JSONObject().apply {
                put("status", "invalid")
                put("kind", "coreRejected")
                put("message", e.message ?: "profile load failed")
            }.toString()
        }
    }

    /**
     * 应用启动时后台加载当前订阅，否则代理/规则/连接/日志页面都拿不到内核数据。
     */
    fun ensureCoreLoaded(context: Context) {
        val uid = ProfileAdapter.getCurrentProfile()
        if (uid.isEmpty()) return
        synchronized(loadLock) {
            if (coreLoaded) return
            try {
                loadProfileConfig(context, uid)
                coreLoaded = true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load profile config: $uid", e)
            }
        }
    }

    private fun loadProfileConfig(context: Context, uid: String) {
        val yaml = ProfileAdapter.readProfileFile(uid)
        check(yaml.isNotEmpty()) { "profile file is empty" }

        // mihomo 以目录为工作目录，内部读取 <dir>/config.yaml
        val profileDir = File(context.filesDir, "clash/$uid")
        if (!profileDir.exists()) profileDir.mkdirs()
        File(profileDir, "config.yaml").writeText(yaml)

        // CMFA 的 config processor 会清空 YAML 里的 external-controller，
        // 只有 Override（在其之后应用）才能重新启用 REST 控制器。
        // writeOverride 会整体覆盖，必须保留已有的模式/日志等级等设置。
        val override = Clash.queryOverride(Clash.OverrideSlot.Persist)
        override.externalController = CONTROLLER_ADDRESS
        override.secret = ""
        Clash.patchOverride(Clash.OverrideSlot.Persist, override)

        // 首次加载会同步下载 geoip/geosite，耗时可达一分钟以上
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(LOAD_TIMEOUT_MS) { Clash.load(profileDir).await() }
        }
        android.util.Log.i(TAG, "Profile config loaded: $uid")
    }

    fun enhanceProfiles(): String {
        // CVR 的增强脚本（merge/script/field）在 Android 端可由 service 模块执行
        return JSONObject().apply { put("status", "valid") }.toString()
    }

    // ===== Verge 配置 =====

    fun getVergeConfig(): String {
        return VergeAdapter.getConfig()
    }

    fun patchVergeConfig(context: Context, patch: JSONObject?): String {
        VergeAdapter.patchConfig(patch)
        if (patch != null && patch.has("enable_tun_mode")) {
            val enable = patch.optBoolean("enable_tun_mode", false)
            if (enable) {
                startTunService(context)
            } else {
                stopTunService(context)
            }
        }
        return "null"
    }

    private fun startTunService(context: Context) {
        val activity = context as? MainActivity
        if (activity != null) {
            activity.ensureVpnThenStart()
        } else if (android.net.VpnService.prepare(context) == null) {
            startTunNow(context)
        }
    }

    fun startTunNow(context: Context) {
        val intent = android.content.Intent(context, VpnTunService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopTunService(context: Context) {
        // VpnService 被系统以 bindService 持有，stopService 不会触发 onDestroy，
        // TUN 和通知会继续存在；必须显式下发 ACTION_STOP 让服务自己收尾。
        if (VpnTunService.isRunning()) {
            context.startService(
                android.content.Intent(context, VpnTunService::class.java)
                    .setAction(VpnTunService.ACTION_STOP),
            )
            return
        }
        context.stopService(android.content.Intent(context, VpnTunService::class.java))
    }

    // ===== 系统 / 网络 =====

    fun getSystemInfo(context: Context): String {
        val info = JSONObject().apply {
            put("system_name", "Android")
            put("system_version", Build.VERSION.RELEASE)
        }
        return info.toString()
    }

    fun getAppUptime(): String {
        return (android.os.SystemClock.elapsedRealtime() - processStartedAt).toString()
    }

    fun isCoreLoaded(): Boolean = coreLoaded

    fun getNetworkInterfaces(): String {
        return JSONObject().apply { put("interfaces", JSONArray()) }.toString()
    }

    fun getNetworkInterfacesInfo(): String = "[]"

    fun getSystemHostname(): String {
        return JSONObject.quote(Build.MODEL)
    }

    fun saveProxyPorts(settings: JSONObject?): String = "null"

    fun probeListener(request: JSONObject?): String = "{}"

    fun getEmbeddedServerPort(): String = "0"

    // ===== 运行时状态 =====

    fun getRuntimeState(): String {
        // 前端 RunStateView 期望 mode/service 为枚举字符串，缺失字段会导致状态判断异常
        return JSONObject().apply {
            put("mode", if (coreLoaded) "Service" else "NotRunning")
            put("service", "ready")
            put("serviceUnavailableReason", JSONObject.NULL)
            put("pendingAction", JSONObject.NULL)
            put("sidecarAllowed", false)
            put("isAdmin", false)
            put("opInFlight", false)
            // Android 无桌面端 service 概念，置 false 可隐藏“卸载服务”入口
            put("serviceUsable", false)
            put("tunCapable", true)
            put("serviceNeedsAttention", false)
        }.toString()
    }

    // ===== 日志 =====

    fun getClashLogs(): String = "[]"

    fun getRuntimeLogs(): String = "[]"

    // ===== DNS =====

    fun getDnsConfigContent(): String = ""

    fun saveDnsConfig(content: String): String = "null"

    fun applyDnsConfig(): String = "null"

    fun validateDnsConfig(): String {
        return JSONObject().apply { put("valid", true) }.toString()
    }

    fun setDnsOverride(profileUid: String, enabled: Boolean): String {
        return JSONObject().apply { put("status", "applied") }.toString()
    }

    // ===== 备份 =====

    fun createLocalBackup(): String = "{}"
    fun listLocalBackup(): String = "[]"
    fun deleteLocalBackup(filename: String): String = "null"
    fun restoreLocalBackup(filename: String): String = "null"
    fun importLocalBackup(source: String?): String = "null"
    fun exportLocalBackup(filename: String): String = "null"
    fun createWebdavBackup(): String = "null"
    fun saveWebdavConfig(url: String, username: String, password: String): String = "null"
    fun listWebdavBackup(): String = "[]"
    fun deleteWebdavBackup(filename: String): String = "null"
    fun restoreWebdavBackup(filename: String): String = "null"

    // ===== 媒体解锁 =====

    private data class UnlockSpec(
        val name: String,
        val url: String,
        val parse: (Int, String) -> Pair<String, String?>,
    )

    private val unlockSpecs: List<UnlockSpec> = listOf(
        UnlockSpec(
            "哔哩哔哩大陆",
            "https://api.bilibili.com/pgc/player/web/playurl?avid=82846771&qn=0&type=&otype=json&ep_id=307247&fourk=1&fnver=0&fnval=16&module=bangumi",
        ) { code, body -> if (code == 200 && body.contains("\"code\":0")) "Yes" to null else "No" to null },
        UnlockSpec(
            "哔哩哔哩港澳台",
            "https://api.bilibili.com/pgc/player/web/playurl?avid=18281381&cid=29892777&qn=0&type=&otype=json&ep_id=183799&fourk=1&fnver=0&fnval=16&module=bangumi",
        ) { code, body -> if (code == 200 && body.contains("\"code\":0")) "Yes" to null else "No" to null },
        UnlockSpec("ChatGPT Web", "https://chat.openai.com/cdn-cgi/trace") { code, body ->
            traceResult(code, body)
        },
        UnlockSpec("Claude", "https://claude.ai/cdn-cgi/trace") { code, body ->
            traceResult(code, body)
        },
        UnlockSpec("Gemini", "https://gemini.google.com") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("Muse AI", "https://muse.ai") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("YouTube Premium", "https://www.youtube.com/premium?hl=en") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("Bahamut Anime", "https://ani.gamer.com.tw/") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("Netflix", "https://www.netflix.com/title/80018499") { code, body ->
            when {
                code !in 200..399 -> "No" to null
                body.contains("Not Available") -> "No" to null
                else -> "Yes" to null
            }
        },
        UnlockSpec("Disney+", "https://www.disneyplus.com/") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("Prime Video", "https://www.primevideo.com") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("Spotify", "https://www.spotify.com/api/content/v1/country-selector?platform=web&format=json") { code, _ ->
            if (code in 200..399) "Yes" to null else "No" to null
        },
        UnlockSpec("TikTok", "https://www.tiktok.com/cdn-cgi/trace") { code, body ->
            traceResult(code, body)
        },
    )

    private fun traceResult(code: Int, body: String): Pair<String, String?> {
        if (code !in 200..399) return "No" to null
        val region = Regex("loc=([A-Za-z]{2})").find(body)?.groupValues?.get(1)?.uppercase()
            ?: return "No" to null
        // 这些地区无法使用 ChatGPT/Claude/TikTok
        return if (region in setOf("CN", "HK", "MO")) "No" to region else "Yes" to region
    }

    fun getUnlockItems(): String = JSONArray().apply {
        unlockSpecs.forEach { spec ->
            put(JSONObject().apply {
                put("name", spec.name)
                put("status", "Pending")
            })
        }
    }.toString()

    fun checkMediaUnlock(): String {
        val client = unlockClient()
        val tasks = unlockSpecs.map { spec ->
            java.util.concurrent.FutureTask { checkOne(client, spec) }.also { Thread(it).start() }
        }
        return JSONArray(tasks.map { it.get() }).toString()
    }

    fun checkMediaUnlockItem(name: String): String {
        val spec = unlockSpecs.firstOrNull { it.name == name }
            ?: return JSONObject().apply { put("name", name); put("status", "Failed") }.toString()
        return checkOne(unlockClient(), spec).toString()
    }

    private fun checkOne(client: okhttp3.OkHttpClient, spec: UnlockSpec): JSONObject {
        val result = try {
            val request = okhttp3.Request.Builder()
                .url(spec.url)
                .header("User-Agent", UNLOCK_UA)
                .build()
            client.newCall(request).execute().use { resp ->
                val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                spec.parse(resp.code, body)
            }
        } catch (e: Exception) {
            "Failed" to null
        }
        return JSONObject().apply {
            put("name", spec.name)
            put("status", result.first)
            put("region", result.second ?: JSONObject.NULL)
            // 前端直接展示该字段，必须返回可读时间而不是 epoch 秒
            put(
                "check_time",
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()),
            )
        }
    }

    private fun unlockClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", mixedPort())))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /**
     * 解锁检测必须经过本地代理，端口以运行中配置为准（设置页改端口后仍可用）。
     */
    fun mixedPort(): Int {
        runCatching {
            val conn = java.net.URL("http://$CONTROLLER_ADDRESS/configs")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val port = JSONObject(body).optInt("mixed-port", 0)
            if (port > 0) return port
        }
        val yaml = ProfileAdapter.readProfileFile(ProfileAdapter.getCurrentProfile())
        val mixed = Regex("^\\s*mixed-port:\\s*(\\d+)", RegexOption.MULTILINE).find(yaml)
        if (mixed != null) return mixed.groupValues[1].toInt()
        val http = Regex("^\\s*port:\\s*(\\d+)", RegexOption.MULTILINE).find(yaml)
        if (http != null) return http.groupValues[1].toInt()
        return 7890
    }

    // ===== 内核生命周期 =====

    fun restartCore(context: Context) {
        CoreAdapter.restart(context)
    }
}

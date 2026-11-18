package io.github.clashverge.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 调用 mihomo external controller 的 HTTP 客户端。
 *
 * mihomo 内核由 CMFA 的 core 模块以 libclash.so 形式加载，
 * 其 external controller 监听在 127.0.0.1 的某个端口。
 */
class MihomoHttpClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var host = "127.0.0.1"
    @Volatile private var port = 9090
    @Volatile private var secret = ""

    fun setController(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    fun setSecret(secret: String) {
        this.secret = secret
    }

    fun baseUrl(): String = "http://$host:$port"

    private fun authHeader(): String? = if (secret.isBlank()) null else "Bearer $secret"

    fun get(url: String): String = execute(Request.Builder().url(url).apply {
        authHeader()?.let { header("Authorization", it) }
    }.build())

    fun patch(url: String, body: JSONObject): String = execute(Request.Builder().url(url)
        .patch(body.toString().toRequestBody(JSON))
        .apply { authHeader()?.let { header("Authorization", it) } }
        .build())

    fun put(url: String, body: JSONObject): String = execute(Request.Builder().url(url)
        .put(body.toString().toRequestBody(JSON))
        .apply { authHeader()?.let { header("Authorization", it) } }
        .build())

    fun post(url: String, body: JSONObject): String = execute(Request.Builder().url(url)
        .post(body.toString().toRequestBody(JSON))
        .apply { authHeader()?.let { header("Authorization", it) } }
        .build())

    fun delete(url: String): String = execute(Request.Builder().url(url).delete()
        .apply { authHeader()?.let { header("Authorization", it) } }
        .build())

    /**
     * 代 WebView 请求任意外部 URL（绕开 CORS）。不带控制器鉴权头。
     * 外网请求使用 ExternalHttp：底层网络 DNS + 绕过 VPN。
     */
    fun fetchExternal(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String> {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        when (method.uppercase()) {
            "POST" -> builder.post((body ?: "").toRequestBody(mediaType))
            "PUT" -> builder.put((body ?: "").toRequestBody(mediaType))
            "PATCH" -> builder.patch((body ?: "").toRequestBody(mediaType))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        // 外部服务（IP 检测等）经本地混合端口代理，既可用节点访问，
        // 也能反映真实出口 IP；直连在部分网络下会被阻断。
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", ClashVergeService.mixedPort()),
        )
        val external = ExternalHttp.client.newBuilder()
            .proxy(proxy)
            .dns(okhttp3.Dns.SYSTEM)
            .build()
        external.newCall(builder.build()).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: "null"
            if (!resp.isSuccessful) {
                // mihomo 错误统一包装成 {__error, detail}
                return JSONObject().apply {
                    put("__error", true)
                    put("detail", text)
                }.toString()
            }
            return text
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

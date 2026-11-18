package io.github.clashverge.android

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 订阅管理适配器。
 *
 * 使用 SharedPreferences 存储订阅元数据，文件系统存储配置文件内容。
 * 后续可替换为 CMFA service 模块的 ProfileManager。
 */
object ProfileAdapter {

    private const val PREFS_NAME = "clashverge_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_CURRENT = "current"

    private lateinit var appContext: Context

    private val httpClient by lazy {
        // 走 ExternalHttp：VPN 开启时仍能正确解析订阅域名并下载
        ExternalHttp.client
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun profilesDir(): File {
        val dir = File(appContext.filesDir, "profiles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCurrentProfile(): String = prefs().getString(KEY_CURRENT, "") ?: ""

    private fun loadProfiles(): JSONArray {
        val raw = prefs().getString(KEY_PROFILES, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
        // 迁移：早期版本将 updated 存为毫秒（>1e12），前端按秒解析导致日期爆炸
        var migrated = false
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val updated = p.optLong("updated", 0)
            if (updated > 1_000_000_000_000L) {
                p.put("updated", updated / 1000)
                migrated = true
            }
        }
        if (migrated) saveProfiles(arr)
        return arr
    }

    private fun saveProfiles(profiles: JSONArray) {
        prefs().edit().putString(KEY_PROFILES, profiles.toString()).apply()
    }

    fun getProfiles(): String {
        val profiles = loadProfiles()
        val current = prefs().getString(KEY_CURRENT, "") ?: ""
        val items = JSONArray()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            items.put(JSONObject().apply {
                put("uid", p.optString("uid"))
                put("name", p.optString("name"))
                put("type", p.optString("type", "remote"))
                put("url", p.optString("url"))
                put("updated", p.optLong("updated", 0))
                put("option", p.optJSONObject("option") ?: JSONObject())
                p.optJSONObject("extra")?.let { put("extra", it) }
            })
        }
        return JSONObject().apply {
            put("current", current)
            put("items", items)
        }.toString()
    }

    fun createProfile(item: JSONObject?, fileData: String?): String {
        if (item == null) return "{}"
        val uid = UUID.randomUUID().toString()
        val name = item.optString("name", "未命名订阅")
        val type = item.optString("type", "remote")
        val url = item.optString("url", "")

        val profile = JSONObject().apply {
            put("uid", uid)
            put("name", name)
            put("type", type)
            put("url", url)
            put("updated", System.currentTimeMillis() / 1000)
            put("option", item.optJSONObject("option") ?: JSONObject())
        }

        val profiles = loadProfiles()
        profiles.put(profile)
        saveProfiles(profiles)

        if (fileData != null) {
            saveProfileFile(uid, fileData)
        }

        return profile.toString()
    }

    fun patchProfile(index: String, patch: JSONObject?): String {
        if (patch == null) return "null"
        val profiles = loadProfiles()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (p.optString("uid") == index) {
                val keys = patch.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    p.put(key, patch.get(key))
                }
                p.put("updated", System.currentTimeMillis() / 1000)
                saveProfiles(profiles)
                return p.toString()
            }
        }
        return "null"
    }

    fun deleteProfile(index: String) {
        val profiles = loadProfiles()
        val newProfiles = JSONArray()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (p.optString("uid") != index) {
                newProfiles.put(p)
            }
        }
        saveProfiles(newProfiles)

        // 删除配置文件
        val file = File(profilesDir(), "$index.yaml")
        if (file.exists()) file.delete()

        // 如果删除的是当前订阅，清空 current
        val current = prefs().getString(KEY_CURRENT, "") ?: ""
        if (current == index) {
            prefs().edit().remove(KEY_CURRENT).apply()
        }
    }

    fun reorderProfile(activeId: String, overId: String) {
        val profiles = loadProfiles()
        val items = (0 until profiles.length()).map { profiles.getJSONObject(it) }.toMutableList()
        val oldIndex = items.indexOfLast { it.optString("uid") == activeId }
        val newIndex = items.indexOfLast { it.optString("uid") == overId }
        if (oldIndex < 0 || newIndex < 0) return
        val item = items.removeAt(oldIndex)
        items.add(newIndex, item)
        saveProfiles(JSONArray(items))
    }

    fun updateProfile(index: String) {
        val profiles = loadProfiles()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (p.optString("uid") != index) continue
            val url = p.optString("url")
            val fetchedName = if (url.isNotEmpty()) fetchAndUpdate(p, url) else null
            if (p.optString("name").isBlank() && fetchedName != null) {
                p.put("name", fetchedName)
            }
            p.put("updated", System.currentTimeMillis() / 1000)
            saveProfiles(profiles)
            break
        }
    }

    /**
     * 检查并更新所有到期的远程订阅。
     *
     * @return 当前正在使用的订阅是否被更新
     */
    fun updateDueProfiles(): Boolean {
        val profiles = loadProfiles()
        val now = System.currentTimeMillis() / 1000
        val current = getCurrentProfile()
        var currentUpdated = false
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (p.optString("type", "remote") != "remote") continue
            if (p.optString("url").isBlank()) continue
            val option = p.optJSONObject("option") ?: continue
            if (!option.optBoolean("allow_auto_update", true)) continue
            val interval = option.optLong("update_interval", 0)
            if (interval <= 0) continue
            val updated = p.optLong("updated", 0)
            if (updated > 0 && now < updated + interval * 60) continue
            val uid = p.optString("uid")
            updateProfile(uid)
            if (uid == current) currentUpdated = true
        }
        return currentUpdated
    }

    /**
     * 下次自动更新时间（Unix 秒），0 表示未开启自动更新。
     */
    fun nextUpdateTime(uid: String): Long {
        val profiles = loadProfiles()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (p.optString("uid") != uid) continue
            if (p.optString("type", "remote") != "remote") return 0
            val option = p.optJSONObject("option") ?: return 0
            if (!option.optBoolean("allow_auto_update", true)) return 0
            val interval = option.optLong("update_interval", 0)
            if (interval <= 0) return 0
            val updated = p.optLong("updated", 0)
            return if (updated > 0) updated + interval * 60 else 0
        }
        return 0
    }

    /**
     * 抓取远程订阅，解析 subscription-userinfo 响应头获取流量与过期时间，
     * 从 content-disposition 解析订阅名称，并保存配置内容。
     *
     * @return 从响应头解析出的订阅名称（不含扩展名），无法解析时返回 null
     */
    private fun fetchAndUpdate(profile: JSONObject, url: String): String? {
        var name: String? = null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "clash-verge-rev/android")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            // 失败必须抛给上层，否则订阅页会静默“刷新成功”，用户看不到任何反馈
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            // 解析订阅流量/过期信息：部分服务端用 subscription-userinfo，部分用 subscription-info
            val subInfo = resp.header("subscription-userinfo")
                ?: resp.header("subscription-info")
            if (subInfo != null) {
                val extra = JSONObject()
                subInfo.split(";").forEach { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2) {
                        val value = kv[1].trim().toLongOrNull() ?: return@forEach
                        when (kv[0].trim()) {
                            "upload" -> extra.put("upload", value)
                            "download" -> extra.put("download", value)
                            "total" -> extra.put("total", value)
                            "expire" -> extra.put("expire", value)
                        }
                    }
                }
                if (extra.length() > 0) profile.put("extra", extra)
            }
            name = parseProfileName(resp.header("content-disposition"))
            // 保存配置文件内容
            resp.body?.string()?.let { body ->
                if (body.isNotBlank()) {
                    saveProfileFile(profile.optString("uid"), body)
                }
            }
        }
        return name
    }

    private fun parseProfileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val encoded = if (contentDisposition.contains("filename*=")) {
            // RFC 5987: filename*=UTF-8''%E9%A5%AD...yml
            contentDisposition.substringAfter("filename*=")
                .substringBefore(';')
                .trim()
                .trim('"')
                .substringAfter("''")
        } else {
            contentDisposition.substringAfter("filename=", "")
                .substringBefore(';')
                .trim()
                .trim('"')
        }
        if (encoded.isBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }
            .getOrDefault(encoded)
        return decoded.substringBeforeLast('.', decoded).ifBlank { null }
    }

    fun viewProfile(index: String): String {
        val profiles = loadProfiles()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (p.optString("uid") == index) {
                return p.toString()
            }
        }
        return "{}"
    }

    fun readProfileFile(index: String): String {
        val file = File(profilesDir(), "$index.yaml")
        return if (file.exists()) file.readText() else ""
    }

    fun saveProfileFile(index: String, fileData: String) {
        val file = File(profilesDir(), "$index.yaml")
        file.writeText(fileData)
    }

    fun importProfile(url: String, option: JSONObject?): String {
        val profile = JSONObject().apply {
            put("uid", UUID.randomUUID().toString())
            put("name", option?.optString("name").orEmpty())
            put("type", "remote")
            put("url", url)
            put("updated", System.currentTimeMillis() / 1000)
            put("option", option ?: JSONObject())
        }
        // 立即抓取订阅内容，名称优先取服务端 content-disposition
        val fetchedName = fetchAndUpdate(profile, url)
        if (profile.optString("name").isBlank()) {
            profile.put("name", fetchedName ?: runCatching { java.net.URI(url).host }.getOrNull() ?: "远程订阅")
        }
        val profiles = loadProfiles()
        profiles.put(profile)
        saveProfiles(profiles)
        return profile.toString()
    }

    fun setCurrentProfile(current: String?) {
        prefs().edit().putString(KEY_CURRENT, current ?: "").apply()
    }

    fun hasCurrentProfile(): Boolean {
        return (prefs().getString(KEY_CURRENT, "") ?: "").isNotEmpty()
    }
}

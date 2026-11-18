package io.github.clashverge.android

import android.app.Application
import com.github.kr328.clash.common.Global
import java.io.File
import java.io.FileOutputStream

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // mihomo JNI bridge 依赖 Global.application 初始化，必须在任何内核调用前设置
        Global.init(this)
        // 旧版本在 TUN 启动时把整个进程绑定到物理网络，进程被停止后该绑定会按 UID 残留，
        // 表现为重启前所有出站请求超时；这里显式清除。
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.bindProcessToNetwork(null)
        }
        ProfileAdapter.init(this)
        VergeAdapter.init(this)
        ExternalHttp.init(this)
        extractGeoFiles()
        // 内核加载耗时较长，放到后台线程避免阻塞启动
        Thread { ClashVergeService.ensureCoreLoaded(this) }.start()
        scheduleProfileAutoUpdate()
    }

    /**
     * mihomo 只会读取 files/clash 下的 geoip.metadb / geosite.dat，
     * 缺失时会自行下载；下载失败会让整个配置加载失败（GEOIP 规则报错），
     * 表现为代理页全部超时、订阅刷新无效。内置数据并在 APK 更新后覆盖。
     */
    private fun extractGeoFiles() {
        val clashDir = File(filesDir, "clash").apply { mkdirs() }
        val updateTime = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        for (name in GEO_ASSETS) {
            val target = File(clashDir, name)
            if (target.exists() && target.lastModified() >= updateTime) continue
            runCatching {
                assets.open(name).use { input ->
                    FileOutputStream(target).use { input.copyTo(it) }
                }
            }.onFailure {
                target.delete()
                android.util.Log.e(TAG, "extract $name failed", it)
            }
        }
    }

    /**
     * 订阅自动更新：JobScheduler 周期检查，到期才真正发起请求。
     */
    private fun scheduleProfileAutoUpdate() {
        runCatching {
            val scheduler = getSystemService(android.app.job.JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(PROFILE_UPDATE_JOB_ID) != null) return
            val job = android.app.job.JobInfo.Builder(
                PROFILE_UPDATE_JOB_ID,
                android.content.ComponentName(this, ProfileAutoUpdateJob::class.java),
            )
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }

    companion object {
        private const val TAG = "MainApplication"
        private const val PROFILE_UPDATE_JOB_ID = 1002
        private val GEO_ASSETS = listOf("geoip.metadb", "geosite.dat")

        lateinit var instance: MainApplication
            private set
    }
}

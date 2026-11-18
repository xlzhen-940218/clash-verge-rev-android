package io.github.clashverge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.github.kr328.clash.core.Clash
import org.json.JSONObject

/**
 * 轻量 VpnService：直接用已加载的内核配置启动 TUN。
 *
 * CMFA 自带的 TunService 依赖其 profile 数据库（ServiceStore / ImportedDao），
 * 本应用使用自己的订阅存储，无法复用，因此在这里只做 VPN 接口与 Clash.startTun 的桥接。
 */
class VpnTunService : VpnService() {

    @Volatile
    private var active = false
    private var worker: Thread? = null
    private val protectLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private val trafficListener = TrafficMonitor.Listener { sample ->
        if (active) updateNotification(sample)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null))
        TrafficMonitor.register(trafficListener)
        VpnTileService.requestUpdate(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知栏停止后同步配置，避免回到界面时 onResume 又把 VPN 拉起来
            VergeAdapter.patchConfig(JSONObject().put("enable_tun_mode", false))
            stopTun()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_STICKY

        ClashVergeService.ensureCoreLoaded(this)
        if (!ClashVergeService.isCoreLoaded()) {
            android.util.Log.e(TAG, "tun start skipped: core not loaded")
            stopSelf()
            return START_NOT_STICKY
        }

        active = true
        running = true
        worker = Thread {
            try {
                // VpnService.Builder 是 VpnService 的非静态内部类，必须在实例作用域内创建
                val builder = Builder()
                    .addAddress(GATEWAY, PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(PORTAL)
                    .setBlocking(false)
                    .setMtu(MTU)
                    .setSession("Clash Verge")
                if (Build.VERSION.SDK_INT >= 29) {
                    builder.setMetered(false)
                }

                val tun = builder.establish()
                    ?: throw IllegalStateException("VPN permission rejected")

                Clash.startTun(
                    fd = tun.detachFd(),
                    stack = "gvisor",
                    gateway = "$GATEWAY/$PREFIX",
                    portal = PORTAL,
                    // 配置里的 tun.dns-hijack 为 0.0.0.0:53，这里必须传 0.0.0.0
                    dns = "0.0.0.0",
                    // 内核自身拨号必须绕过 tun，否则出站流量重新进入 tun 形成环路
                    markSocket = { fd ->
                        val ok = protect(fd)
                        if (ok) {
                            if (protectLogged.compareAndSet(false, true)) {
                                android.util.Log.i(TAG, "kernel socket protect ok")
                            }
                        } else {
                            android.util.Log.w(TAG, "kernel socket protect failed fd=$fd")
                        }
                        ok
                    },
                    querySocketUid = { protocol, source, target ->
                        queryConnectionOwnerUid(protocol, source, target)
                    },
                )
                android.util.Log.i(TAG, "tun started")
                MainActivity.notifyVpnConfigChanged()
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "tun start failed", e)
                stopSelf()
            }
        }.also { it.start() }

        return START_STICKY
    }

    override fun onRevoke() {
        stopTun()
        super.onRevoke()
    }

    override fun onDestroy() {
        TrafficMonitor.unregister(trafficListener)
        stopTun()
        if (instance === this) instance = null
        VpnTileService.requestUpdate(this)
        super.onDestroy()
    }

    private fun stopTun() {
        if (!active) return
        active = false
        running = false
        runCatching { Clash.stopTun() }
        worker = null
        MainActivity.notifyVpnConfigChanged()
    }

    private fun queryConnectionOwnerUid(
        protocol: Int,
        source: java.net.InetSocketAddress,
        target: java.net.InetSocketAddress,
    ): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.getConnectionOwnerUid(protocol, source, target) ?: -1
        }.getOrDefault(-1)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(sample: TrafficMonitor.Sample) {
        val notification = buildNotification(sample)
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(sample: TrafficMonitor.Sample?): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
            flags,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VpnTunService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val contentText = if (sample != null) {
            "↑ ${TrafficFormat.speed(sample.up)}   ↓ ${TrafficFormat.speed(sample.down)}"
        } else {
            "VPN 运行中"
        }
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Clash Verge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "VpnTunService"
        private const val CHANNEL_ID = "clash-verge-vpn"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "io.github.clashverge.android.action.STOP_VPN"
        private const val MTU = 9000
        private const val PREFIX = 30
        private const val GATEWAY = "172.19.0.1"
        private const val PORTAL = "172.19.0.2"

        @Volatile
        var running = false

        @Volatile
        private var instance: VpnTunService? = null

        fun isRunning(): Boolean = running

        /**
         * 让应用自身的出站 socket 绕过 VPN，避免 fake-ip 与代理环路。
         */
        fun protect(socket: java.net.Socket): Boolean =
            runCatching { instance?.protect(socket) == true }.getOrDefault(false)
    }
}

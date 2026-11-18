package com.github.kr328.clash.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import com.github.kr328.clash.core.Clash
import java.net.InetSocketAddress

/**
 * VpnService 实现，将 TUN 设备 fd 交给 mihomo 内核处理。
 */
class TunService : VpnService() {

    private var tunFd: Int = -1
    private var running = false

    override fun onCreate() {
        super.onCreate()
        startTun()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopTun()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTun()
        stopSelf()
        super.onRevoke()
    }

    private fun startTun() {
        if (running) return
        runCatching {
            val fd = establishVpn() ?: return
            tunFd = fd
            running = true

            Clash.startTun(
                fd = fd,
                stack = TUN_STACK,
                gateway = "$TUN_GATEWAY/$TUN_PREFIX",
                portal = TUN_PORTAL,
                dns = TUN_DNS,
                markSocket = { socket -> protect(socket) },
                querySocketUid = { protocol, source, target -> queryUid(protocol, source, target) },
            )
        }.onFailure {
            android.util.Log.e(TAG, "startTun failed", it)
        }
    }

    private fun stopTun() {
        running = false
        runCatching { Clash.stopTun() }
        if (tunFd >= 0) {
            runCatching {
                val cls = Class.forName("android.system.Os")
                val close = cls.getMethod("close", Int::class.javaPrimitiveType)
                close.invoke(null, tunFd)
            }
            tunFd = -1
        }
    }

    private fun establishVpn(): Int? {
        val builder = Builder()
            .setSession("Clash Verge")
            .setMtu(TUN_MTU)
            .addAddress(TUN_GATEWAY, TUN_PREFIX)
            .addRoute(NET_ANY, 0)
            .addDnsServer(TUN_DNS)
            .setBlocking(false)

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }

        return builder.establish()?.detachFd()
    }

    private fun queryUid(
        protocol: Int,
        source: InetSocketAddress,
        target: InetSocketAddress,
    ): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getConnectionOwnerUid(protocol, source, target)
        }.getOrDefault(-1)
    }

    companion object {
        private const val TAG = "TunService"
        private const val TUN_MTU = 9000
        private const val TUN_PREFIX = 30
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_PORTAL = "172.19.0.2"
        private const val TUN_DNS = TUN_PORTAL
        private const val NET_ANY = "0.0.0.0"
        private const val TUN_STACK = "gvisor"
    }
}

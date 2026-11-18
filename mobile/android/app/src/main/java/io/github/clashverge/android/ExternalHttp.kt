package io.github.clashverge.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * 应用自身的出站 HTTP 客户端。
 *
 * VPN 开启后系统 DNS 会返回 fake-ip（198.18.x.x），且流量可能绕进 TUN，
 * 导致订阅更新、IP 信息等原生请求失败。这里固定：
 * 1. 用底层物理网络（Wi-Fi/蜂窝）解析 DNS；
 * 2. 通过 VpnService.protect 让这些 socket 绕过 VPN。
 */
object ExternalHttp {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun underlyingNetwork(): Network? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val network = underlyingNetwork()
            return if (network != null) {
                network.getAllByName(hostname).toList()
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private val protectedSocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = Socket().also { VpnTunService.protect(it) }

        override fun createSocket(host: String?, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .socketFactory(protectedSocketFactory)
            .dns(dns)
            .build()
    }
}

package io.github.clashverge.android

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import org.json.JSONObject

/**
 * 下拉快捷面板磁贴：一键开启 / 停止 VPN。
 *
 * HyperOS 会把常驻通知的 action 隐藏起来，磁贴提供一个稳定的开关入口。
 */
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (VpnTunService.isRunning()) {
            stopVpn()
        } else {
            startVpn()
        }
        // 服务启停是异步的，稍后再刷新一次状态
        android.os.Handler(mainLooper).postDelayed({ updateTile() }, 800)
    }

    private fun startVpn() {
        VergeAdapter.patchConfig(JSONObject().put("enable_tun_mode", true))
        if (VpnService.prepare(this) != null) {
            // 未授权：跳到应用内完成授权，授权后 MainActivity 会继续拉起 TUN
            toast("请先授权 VPN")
            openApp()
            return
        }
        ClashVergeService.startTunNow(this)
    }

    private fun stopVpn() {
        VergeAdapter.patchConfig(JSONObject().put("enable_tun_mode", false))
        val intent = Intent(this, VpnTunService::class.java)
            .setAction(VpnTunService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = VpnTunService.isRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running) "已连接" else "未连接"
        }
        tile.updateTile()
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        fun requestUpdate(context: android.content.Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, VpnTileService::class.java),
                )
            }
        }
    }
}

package io.github.clashverge.android

import android.app.job.JobParameters
import android.app.job.JobService
import org.json.JSONObject

/**
 * 订阅自动更新：按 profile.option.update_interval 判断是否到期，
 * 只更新到期的远程订阅，避免每次唤醒都打服务器。
 */
class ProfileAutoUpdateJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            val currentUpdated = runCatching { ProfileAdapter.updateDueProfiles() }
                .getOrDefault(false)
            if (currentUpdated) {
                // 当前订阅内容变了，运行中的内核必须重新加载才会生效
                runCatching {
                    ClashVergeService.patchProfilesConfig(
                        this,
                        JSONObject().put("current", ProfileAdapter.getCurrentProfile()),
                    )
                }
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

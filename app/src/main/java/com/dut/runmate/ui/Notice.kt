package com.dut.runmate.ui

import android.app.Activity
import androidx.fragment.app.Fragment
import com.dut.runmate.R

/**
 * v1.8.11：统一「需手动确认」弹窗 —— 替代 App 内全部 Toast。
 *
 * 用户实报：临时弹窗（Toast）文本显示不全（长文案被截断，无法完整阅读），
 * 要求：不要用临时弹窗，所有弹窗必须用户手动确认后才能关闭。
 *
 * 规则：
 *  - MaterialAlertDialog + 单「确定」按钮；
 *  - setCancelable(false)：点弹窗外侧 / 按返回键都不关闭，必须点「确定」；
 *  - 全链路 try 隔离 + 生命周期守卫：Fragment 已脱离宿主 / Activity 已 finishing
 *    时静默放弃，绝不因弹窗闪退（对齐 v1.8.10 设置页延迟回调闪退的教训）。
 *
 * 例外：RunTrackerService 后台无法弹对话框（无 Activity 窗口），其启动失败
 * 提示改走系统通知（可展开全文，点按进入 App）——见 RunTrackerService。
 */
object Notice {

    private fun showImpl(activity: Activity?, title: String?, msg: String) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(title ?: activity.getString(R.string.notice_title))
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) {
        }
    }

    // ---------- Activity 版 ----------

    @JvmOverloads
    fun show(activity: Activity?, msg: String, title: String? = null) =
        showImpl(activity, title, msg)

    @JvmOverloads
    fun show(activity: Activity?, msgRes: Int, titleRes: Int = R.string.notice_title) {
        if (activity == null) return
        showImpl(activity, activity.getString(titleRes), activity.getString(msgRes))
    }

    // ---------- Fragment 版（宿主已脱离时静默放弃） ----------

    @JvmOverloads
    fun show(fragment: Fragment?, msg: String, title: String? = null) =
        showImpl(fragment?.activity, title, msg)

    @JvmOverloads
    fun show(fragment: Fragment?, msgRes: Int, titleRes: Int = R.string.notice_title) {
        val act = fragment?.activity ?: return
        showImpl(act, act.getString(titleRes), act.getString(msgRes))
    }
}

package com.dut.runmate.ui

import android.content.Context
import com.dut.runmate.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * v1.6.1：接口页 / H5 捕获页共用的「说明」对话框。
 * 背景：用户反馈不清楚「令牌 Token」「自定义接口」以及登录后 H5 页
 * 三个快捷入口（健康长跑校内直连 / WebVPN 登录 / 重新注入嗅探器）的用途，
 * 把说明直接放进 App，随装随查。
 */
object HelpDialog {
    fun show(ctx: Context) {
        try {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.api_help_title)
                .setMessage(R.string.api_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) {
        }
    }
}

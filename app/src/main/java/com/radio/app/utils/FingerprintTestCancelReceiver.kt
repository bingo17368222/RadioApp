package com.radio.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v3.1.64: 指纹测试取消广播接收器。
 *
 * 声明在 AndroidManifest.xml 中，因此无论 Activity 是否存活，
 * 用户点击通知栏"取消"按钮时都能收到广播并取消指纹测试。
 *
 * setPackage() 在 Android 12+ 上使广播变为显式广播，仅传递到
 * AndroidManifest 中声明的接收器，动态注册的接收器无法收到。
 * 因此必须使用 manifest 声明的接收器。
 */
class FingerprintTestCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != FingerprintTestNotificationHelper.CANCEL_ACTION) return

        // 设置取消标志
        FingerprintTestNotificationHelper.setCancelled()
        // 移除通知
        FingerprintTestNotificationHelper.cancel(context)
    }
}
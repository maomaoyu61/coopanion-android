package com.maomaoyu.coopanionpet

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 只关心"微信/QQ 来消息了"这一件事 —— **不读取消息内容**（只报 App 名字），
 * 需要在系统「通知使用权」里手动开启，没开就什么也不做。
 */
class PetNotifyListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val app = when (pkg) {
            "com.tencent.mm" -> "微信"
            "com.tencent.mobileqq" -> "QQ"
            "com.tencent.tim" -> "TIM"
            else -> return
        }
        // 忽略自己
        if (sbn.packageName == applicationContext.packageName) return
        try {
            PetService.instance?.onAppMessage(app)
        } catch (_: Exception) {
        }
    }
}

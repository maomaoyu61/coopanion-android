package com.maomaoyu.coopanionpet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏那个「触摸穿透 / 恢复操作桌宠」按钮。 */
class PassthroughReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PetService.instance?.togglePassthrough()
    }
}

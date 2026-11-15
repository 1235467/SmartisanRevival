package com.smartisanrevival.onestep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.smartisanrevival.onestep.service.OneStepAccessibilityService

/**
 * 调试入口（对照 bang 的 DebugTriggerReceiver，但补上 intent-filter）：
 *   adb shell am broadcast -a com.smartisanrevival.onestep.DEBUG_TOGGLE
 *   adb shell am broadcast -a com.smartisanrevival.onestep.DEBUG_SHOW
 *   adb shell am broadcast -a com.smartisanrevival.onestep.DEBUG_FLASH_HOTZONE
 */
class OneStepDebugReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DEBUG_ENABLE = "com.smartisanrevival.onestep.DEBUG_ENABLE"
        const val ACTION_DEBUG_DISABLE = "com.smartisanrevival.onestep.DEBUG_DISABLE"
        const val ACTION_DEBUG_TOGGLE = "com.smartisanrevival.onestep.DEBUG_TOGGLE"
        const val ACTION_DEBUG_SHOW = "com.smartisanrevival.onestep.DEBUG_SHOW"
        const val ACTION_DEBUG_HIDE = "com.smartisanrevival.onestep.DEBUG_HIDE"
        const val ACTION_DEBUG_FLASH = "com.smartisanrevival.onestep.DEBUG_FLASH_HOTZONE"
        const val ACTION_DEBUG_SPLIT = "com.smartisanrevival.onestep.DEBUG_SPLIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val svc = OneStepAccessibilityService.instance
        when (intent.action) {
            ACTION_DEBUG_ENABLE -> {
                OneStepPrefs.setEnabled(context, true)
                svc?.startTriggers()
                Toast.makeText(context, "一步已开启", Toast.LENGTH_SHORT).show()
            }
            ACTION_DEBUG_DISABLE -> {
                OneStepPrefs.setEnabled(context, false)
                svc?.stopTriggers()
                Toast.makeText(context, "一步已关闭", Toast.LENGTH_SHORT).show()
            }
            ACTION_DEBUG_TOGGLE -> {
                if (svc == null) toast(context, "无障碍「一步引擎」未连接") else svc.toggleSidebar()
            }
            ACTION_DEBUG_SHOW -> {
                if (svc == null) toast(context, "无障碍「一步引擎」未连接") else svc.showSidebar()
            }
            ACTION_DEBUG_HIDE -> {
                if (svc == null) toast(context, "无障碍「一步引擎」未连接") else svc.dismissSidebar()
            }
            ACTION_DEBUG_FLASH -> {
                if (svc == null) toast(context, "无障碍「一步引擎」未连接") else svc.flashHotzone()
            }
            ACTION_DEBUG_SPLIT -> {
                // 实验：让当前应用进入系统分屏（活窗口），观察 Flyme 支持
                if (svc == null) {
                    toast(context, "无障碍「一步引擎」未连接")
                } else {
                    val ok = svc.trySplitScreen()
                    toast(context, if (ok) "已请求分屏" else "分屏动作不可用")
                }
            }
        }
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

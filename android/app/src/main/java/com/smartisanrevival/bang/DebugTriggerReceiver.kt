package com.smartisanrevival.bang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 调试用：adb 手动触发炸词
 *   adb shell am broadcast -a com.smartisanrevival.bang.TRIGGER
 *   adb shell am broadcast -a com.smartisanrevival.bang.TRIGGER --ei x 684 --ei y 1750
 * 带坐标时走真实按压链路（取字 → 注入抬起 → 面板），不带坐标炸当前页最大文本。
 * 仅本机调试使用，正式分发时从 manifest 移除。
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("Bang", "receiver fired")
        val svc = com.smartisanrevival.bang.service.BangAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(context, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val x = intent.getIntExtra("x", -1)
        val y = intent.getIntExtra("y", -1)
        if (x >= 0 && y >= 0) svc.pressBang(x, y) else svc.triggerManual()
    }
}

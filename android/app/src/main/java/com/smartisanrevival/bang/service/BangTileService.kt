package com.smartisanrevival.bang.service

import android.service.quicksettings.TileService
import android.widget.Toast

/** 通知栏磁贴「炸词」：兜底入口（docs/02 §二 兜底表） */
class BangTileService : TileService() {
    override fun onClick() {
        val svc = BangAccessibilityService.instance
        if (svc == null) {
            // TileService 与无障碍服务同进程；走到这说明无障碍没开
            Toast.makeText(applicationContext, "先在设置里打开「大爆炸引擎」无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        svc.triggerManual()
    }
}

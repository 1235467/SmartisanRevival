package com.smartisanrevival.onestep

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.smartisanrevival.onestep.service.OneStepAccessibilityService

/**
 * 快捷磁贴「一步」：点击开/关侧栏（原版硬件键呼出的替代入口）。
 */
class OneStepTileService : TileService() {

    override fun onClick() {
        val svc = OneStepAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍「一步引擎」未连接", Toast.LENGTH_SHORT).show()
            return
        }
        svc.toggleSidebar()
    }

    override fun onStartListening() {
        updateTile()
    }

    private fun updateTile() {
        qsTile?.let {
            it.state = if (OneStepPrefs.enabled(applicationContext)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }
}

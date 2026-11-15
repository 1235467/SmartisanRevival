package com.smartisanrevival.onestep.send

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.smartisanrevival.onestep.data.ContentStores

/**
 * 收纳入口（替代原版「拖拽分享快捷方式」的落点）：
 *   - ACTION_PROCESS_TEXT：任意 App 选中文字 → 输入法工具条「一步」
 *   - ACTION_SEND：系统分享菜单 → 一步
 * 收进剪贴板历史，侧栏剪贴板面板可见、可一键复制走。
 */
class OneStepSendActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text: String? = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "没有可收纳的文本", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ContentStores.addClip(this, text)
        Toast.makeText(this, "已存入一步（${text.length} 字）", Toast.LENGTH_SHORT).show()
        finish()
    }
}

# SmartisanRevival · 锤子还魂

在魅族 21 Pro（Flyme / Android 16）上 1:1 还原 Smartisan OS 8 的招牌能力。  
当前主线是**大爆炸**。不用刷机、不用 root；触发靠 Shizuku 读触摸屏，取字靠无障碍。

私有仓库。目标机：USB adb `481QHFFN229CW`。参考机：坚果 R2（Smartisan OS 8）。

---

## 现在能做什么

拇指按住屏幕上的字约 **0.55 秒**（要拇指肚，指尖轻点不会炸）：

- 备忘录、QQ、微信消息、系统设置等**能复制的文字**会炸开成词块
- 底部圆角面板，词块从按压点飞出；原文换行保留
- 划选之后：复制、搜索、词典、切开、分享
- **复制**：原版 24 帧开口动画，字吸进去，按钮合回圆钮，面板不关
- **搜索 / 词典**：独立浮卡「网络搜索结果」，底栏换百度 / 谷歌 / 必应 / 有道等

按在按钮、空白处不炸，系统自己的长按菜单照常。

---

## 还没做

| 项 | 说明 |
|----|------|
| **OCR** | 图片里的字、微信部分自绘气泡。无障碍树没有字时，截屏 + RapidOCR/PaddleOCR 再炸 |
| 词典词条面板 | 现在词典走搜索浮卡里的有道页；原版还有蓝条加载 + 词条列表 |
| 上拉分段 / 双指炸 | 长文追加下一段、双指同时按 |
| Magisk / LSPosed | 真正替换系统长按。现在是外挂读触摸，聊天软件自己的菜单偶尔还会露一下 |
| 一步 / 闪念胶囊 / 无限屏 | 大爆炸之后的下一几项，见 `docs/00-可行性总表.md` |

---

## 仓库结构

```
android/          大爆炸 App（com.smartisanrevival.bang）
probe/            第 0 期体检 APK
docs/             施工文档；CURRENT.md 是当前边界
references/
  analysis/       坚果 R2 录屏逐帧
  recordings/     真机截图、bang_demo.mp4
  smartisan-apk/  OS 8 TextBoom 解包素材
                  （官方源码 clone 在 references/repos/，不进 git）
```

---

## 在这台电脑上编译

Gradle 必须在 `android/` 里跑，仓库根目录没有 `settings.gradle`。

```
JDK    D:\tools\jdk-17
Gradle D:\tools\gradle\gradle-8.9
ADB    D:\Project\SmartisanRevival\AndroidSdk\platform-tools\adb.exe
```

```bat
cd /d D:\Project\SmartisanRevival\android
D:\tools\gradle\gradle-8.9\bin\gradle.bat assembleDebug --console=plain
```

APK：`android/app/build/outputs/apk/debug/app-debug.apk`

```bat
adb -s 481QHFFN229CW install -r android\app\build\outputs\apk\debug\app-debug.apk
```

重装后 Flyme 会关掉无障碍，要到 **设置 → 辅助功能 → 无障碍 → 大爆炸引擎** 再打开一次。

---

## 真机怎么开

1. 安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku)（无线调试即可，不用 root），对大爆炸点「始终允许」
2. 打开无障碍「大爆炸引擎」
3. 打开大爆炸 App，点「② 启用拇指按压」
4. 用**拇指肚**按住文字约半秒

重启之后 Shizuku 不会自己起来，无障碍也可能被 Flyme 清掉。先开 Shizuku，再开无障碍。细节见 `docs/CURRENT.md`。

调试广播（不经过手指）：

```bat
adb -s 481QHFFN229CW shell am broadcast -a com.smartisanrevival.bang.TRIGGER --ei x 684 --ei y 1100 -n com.smartisanrevival.bang/.DebugTriggerReceiver
```

日志：`adb logcat -s Bang:I BangPress:I`  
抬起时会打 `major=`（接触面积 0–255）和 `held=`（按住毫秒）。轻点应该只有 `up ... fired=false`，没有 `PRESS`。

---

## 触发判定（现行）

触摸设备 `main_touch`（`/dev/input/event7`），坐标 13680×31920。

同时满足才炸：

1. **时间**：按住 ≥ 550ms，中途没滑出约 40dp
2. **面积**：`ABS_MT_TOUCH_MAJOR` 峰值 ≥ 32（满量程 255）。指尖轻点通常 <20，拇指肚会上去
3. **有字**：无障碍树在手指下面能读到可复制的正文

QQ / 微信把气泡标成敏感节点，服务必须声明为辅助功能工具（`isAccessibilityTool`）才能读到。

---

## 文档

| 文件 | 内容 |
|------|------|
| `docs/CURRENT.md` | 当前能力、装机、重启后怎么修 |
| `docs/02-大爆炸.md` | 施工图、动效数值、验收 |
| `docs/00-可行性总表.md` | 六个动作的路线 |
| `docs/notes/bigbang-algorithm.md` | 官方源码 + R2 APK 的算法摘录 |

原版大爆炸 App 只是显示端。触发和取字在锤子 framework 里，第三方做不到那一层，所以用 Shizuku 读 `/dev/input` 补上。

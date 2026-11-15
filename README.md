# SmartisanRevival · 锤子还魂

在魅族 21 Pro（Flyme AIOS / Android 16）上 1:1 还原 Smartisan OS 8 的招牌能力。
当前两条主线都已可用：**大爆炸**（拇指按压炸词）和**一步**（侧栏 + 拇指下拉）。
不用刷机、不用 root；触发靠 Shizuku 读触摸屏，取字靠无障碍。

首个体验版已发 GitHub Release：[`v1.0-thumbpress`](https://github.com/nt-cubic/SmartisanRevival/releases/tag/v1.0-thumbpress)。
**Shizuku 已内置在 APK 里**，接收人全程不需要联网。

私有仓库。目标机：USB adb `481QHFFN229CW`。参考机：坚果 R2（Smartisan OS 8）。

---

## 现在能做什么

### 大爆炸 · 拇指按压炸词

拇指按住屏幕上的字约 **0.55 秒**（要拇指肚，指尖轻点不会炸）：

- 备忘录、QQ、微信消息、系统设置等**能复制的文字**会炸开成词块
- 底部圆角面板，词块从按压点飞出；原文换行保留；长文分段追加
- 划选之后：复制、搜索、词典、切开、分享
- **复制**：原版 24 帧开口动画，字吸进去，按钮合回圆钮，面板不关
- **搜索 / 词典**：独立浮卡「网络搜索结果」，底栏换百度 / 谷歌 / 必应 / 有道等
- **截图 OCR 兜底**（刚接上）：无障碍树没字的界面走截屏 + RapidOCR 本地识别，模型首次加载要几秒

按在按钮、空白处不炸，系统自己的长按菜单照常。

### 一步 · OS 8 形态

- **右上角向内划**呼出侧栏（闪现热区），最多钉 6 个应用，点图标直接启动，长按移除
- 顶栏三个面板：**剪贴板 / 文件 / 照片**（照片需授权相册）
- **拇指下拉**（原版手势）：拇指肚大面积按住上半屏往下拖出侧栏，默认关，误触可调灵敏度
- 分享/选字收纳：任何 App 的「分享」和文本选择菜单里会出现一步入口（`PROCESS_TEXT`）
- 已知折损：侧栏覆盖应用边缘而非挤压窗口；钉住的是图标不是活窗口。真收缩窗口需要 root，见 `docs/notes/root-research-meizu21pro.md`

---

## 发布给别人装（v1.0-thumbpress）

APK 在 Release 附件或 `dist/SmartisanRevival-1.0-thumbpress.apk`（43 MB，arm64-v8a，自签名）。

接收人流程（**全程无需联网**）：

1. 安装 APK
2. 打开 App 点 **②「安装/授权 Shizuku」**：从内置包弹出系统安装器装 Shizuku；再打开 Shizuku →「通过无线调试启动」→ 配对（一次性）
3. 开无障碍「大爆炸引擎」「一步引擎」；Android 13+ 若提示"受限设置"：应用信息 → 右上角 ⋮ → **允许受限设置**
4. 按主界面引导校准拇指、钉应用、加磁贴

限制：Android 11+（Shizuku 无线调试的最低要求）；非 root 设备**每次重启后**要在 Shizuku 里点一下「启动」。

---

## 还没做

| 项 | 说明 |
|----|------|
| 词典词条面板 | 现在词典走搜索浮卡里的有道页；原版还有蓝条加载 + 词条列表 |
| 上拉分段 / 双指炸 | 长文追加下一段（已做下拉方向）、双指同时按 |
| OCR 体验打磨 | 截图炸刚接上，模型首载慢、入口待理顺 |
| Magisk / LSPosed | 真正替换系统长按。现在是外挂读触摸，聊天软件自己的菜单偶尔还会露一下 |
| 闪念胶囊 / 无限屏 | 下一批，见 `docs/notes/ideapills-spec.md` |
| root 链路（可选） | GhostLock 临时 root / KernelSU，解锁真窗口收缩与 framework 级触发，见 `docs/notes/root-research-meizu21pro.md` |

---

## 仓库结构

```
android/            App（com.smartisanrevival.bang + com.smartisanrevival.onestep）
  app/src/main/
    bang/           大爆炸：press（evdev 触发）、text（jieba 分词）、ocr（截图兜底）、
                    overlay（面板）、shell（Shizuku UserService）
    onestep/        一步：overlay（侧栏/热区/收缩卡）、press（拇指下拉）、
                    send（分享收纳）、shell、service
  app/src/debug/    仅调试包含 adb 广播接收器（release 不含）
  keystore/         签名密钥（不进 git，本地备份）
dist/               发布产物（不进 git）
probe/              第 0 期体检 APK 与转储
docs/               施工文档；CURRENT.md 是当前边界
references/
  analysis/         坚果 R2 录屏逐帧
  recordings/       真机截图、bang_demo.mp4
  smartisan-apk/    OS 8 TextBoom 解包素材
                    （官方源码 clone 在 references/repos/，不进 git）
```

---

## 在这台电脑上编译

Gradle 必须在 `android/` 里跑，仓库根目录没有 `settings.gradle`。

```
JDK    D:\tools\jdk-17
Gradle D:\tools\gradle\gradle-8.9
SDK    D:\Android\Sdk（local.properties）
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

**发 release 版**（签名密钥在 `android/keystore/bang-release.jks`，别名 `bang`，密码见私档；密钥不随仓库分发）：

```bat
D:\tools\gradle\gradle-8.9\bin\gradle.bat assembleRelease --console=plain
```

APK：`android/app/build/outputs/apk/release/app-release.apk`，发版前复制到 `dist/` 并走 `gh release create`。

---

## 真机怎么开（开发机）

1. 装好 Shizuku（现在直接用 App 内 ② 内置包装即可），无线调试激活，对大爆炸点「始终允许」
2. 打开无障碍「大爆炸引擎」「一步引擎」
3. 大爆炸点「②」授权；一步点「⑥ 开启一步」
4. 用**拇指肚**按住文字约半秒

重启之后 Shizuku 不会自己起来，无障碍也可能被 Flyme 清掉。先开 Shizuku，再开无障碍。细节见 `docs/CURRENT.md`。

调试广播（**仅 debug 构建**，release 已移除接收器；不经过手指）：

```bat
adb -s 481QHFFN229CW shell am broadcast -a com.smartisanrevival.bang.TRIGGER --ei x 684 --ei y 1100 -n com.smartisanrevival.bang/.DebugTriggerReceiver
```

一步侧栏调试（同样仅 debug）：

```bat
adb -s 481QHFFN229CW shell am broadcast -a com.smartisanrevival.onestep.DEBUG_TOGGLE
```

日志：`adb logcat -s Bang:I BangPress:I OneStep:I`  
抬起时会打 `major=`（接触面积 0–255）和 `held=`（按住毫秒）。轻点应该只有 `up ... fired=false`，没有 `PRESS`。

---

## 触发判定（现行）

触摸设备 `main_touch`（`/dev/input/event7`），坐标 13680×31920。

同时满足才炸：

1. **时间**：按住 ≥ 550ms，中途没滑出约 40dp
2. **面积**：`ABS_MT_TOUCH_MAJOR` 峰值 ≥ 主界面校准值的 78%（未校准默认 120，满量程 255）。指尖轻点通常 <20，拇指肚会上去
3. **有字**：无障碍树在手指下面能读到可复制的正文；读不到时走截图 OCR 兜底

QQ / 微信把气泡标成敏感节点，服务必须声明为辅助功能工具（`isAccessibilityTool`）才能读到。

---

## 文档

| 文件 | 内容 |
|------|------|
| `docs/CURRENT.md` | 当前能力、装机、重启后怎么修 |
| `docs/02-大爆炸.md` | 施工图、动效数值、验收 |
| `docs/00-可行性总表.md` | 六个动作的路线 |
| `docs/notes/onestep-spec.md` | 一步的形态、交互与已知折损 |
| `docs/notes/ideapills-spec.md` | 闪念胶囊（下一批） |
| `docs/notes/root-research-meizu21pro.md` | 21 Pro root / BL 解锁调研 |
| `docs/notes/bigbang-algorithm.md` | 官方源码 + R2 APK 的算法摘录 |

原版大爆炸 App 只是显示端。触发和取字在锤子 framework 里，第三方做不到那一层，所以用 Shizuku 读 `/dev/input` 补上。

> 注：本笔记引用的 `references/recordings/`、`references/smartisan-apk/` 素材仅存本地，不随公开仓库分发。
# 一步 OneStep · 复刻规格（R2 实测 + 官方源码对表）

> 2026-11-15 第 0 步产出。来源：坚果 R2（Smartisan OS / Android 11，aries）实机操作 + 截屏归档；
> 官方 `packages_apps_OneStep` 源码（Apache-2.0，OS 3.x）；OS 8 `com.smartisanos.sidebar.apk` 资源表 dump。

## 触发手势（原版有三种）

| 手势 | 依据 | 魅族可行性 |
|------|------|-----------|
| **拇指大面积下拉**（原版主手势） | R2 设置页原文："使用拇指以较大面积皮肤接触屏幕并由上向下拖拽时呼出一步"；资源名 `no_widget_thumb_dropdown`；「拇指下拉触发面积」5 档可调（smallest→largest，越小越易触发） | ✅ 魅族 main_touch (`/dev/input/event7`) 上报 `ABS_MT_TOUCH_MAJOR`（max 255），evdev 可判接触面积。技术路线与 BigBang 拇指按压同源（Shizuku UserService 读 evdev 二进制流）。注意：**观察型**（无法吞事件），下拉若起始于状态栏会同时拉出通知栏——起始点应过滤到状态栏以下 |
| 右上角内划 | R2 实测 `input swipe 1060 90 → 850 650` 直接触发（录屏 `r2_trig_anim.mp4`）；官方源码 `BIT_SIDEBAR_IN_RIGHT_TOP_MODE` | ✅ 可做：角落热区窗口 + `setSystemGestureExclusionRects`（≤200dp 预算） |
| 快捷键 + 音量下 | R2 设置页（硬件键） | ❌ 无硬件键，磁贴替代 |

用户已拍板（2026-11-14）：角落热区 + 磁贴兜底为 v1 主线；拇指大面积下拉作为「原版手势」选项一并实现（触摸面积数据可用性已验证）。

## OS 8 形态（R2 实测，复刻目标形态）

进入一步模式后的整屏结构（截屏 `r2_gest_tr.png` / `r2_mini_tap.png` / `r2_trig_anim.mp4` 帧）：

```
┌──────────────────────────────────────────────┬─────┐
│ ↖退出  ⚙  One Step  ← →                      │     │  ← 标题条：退出/设置/标题/切换应用
├──────────────────────────────────────────────┤     │
│ [B站][@][易][微博][Twitter][Insta][Play][TG]… │ 栏  │  ← dock 行：拖拽分享目标（横向滚动）
├──────────────────────────────────────────────┤     │
│                                              │ ⊕   │
│              应用区（原版为被挤压的真窗口）      │ ⊕   │  ← 侧栏："+" 槽位（钉当前应用）
│         （魅族上只能覆盖式，做不到挤压）        │ ⊕   │     钉住后显示为活的小窗缩略
│                                              │     │
└──────────────────────────────────────────────┴─────┘
```

- 点 "+" 槽位 = 把当前应用钉进侧栏（实测：钉住后变迷你窗，原 SearchBox 缩到槽内）
- 点迷你窗 = 展开到应用区（实测，展开后整屏布局镜像到另一侧）
- 触发侧 = 栏所在侧（右上角下拉 → 栏在右；`sidebar_top_widget_state` 为顶部挂件开关，v2）
- 原版应用区是被 framework 裁剪的真窗口（uidump 显示应用自以为全屏）——**第三方无法挤压其他应用**，用覆盖近似，列为已知折损

## OS 3.x 结构（官方源码，内容面板的实现规格）

侧栏条（48dp，OS 8 实测约 66dp）自上而下：ongoinglist / contactlist / applist；拖拽外部内容进入时切换到
sharelist（分享目标列表）。顶栏三按钮：照片 / 文件 / 剪贴板 → 内容面板（圆角卡片、标题条 + 清除按钮 + 列表）。

- 照片面板：3 张/行，`recent_photo_size` 80dp（sw411dp 96dp），日期分组（今天/昨天/7天/30天/更早），「打开相册」「更多」格
- 文件面板：mime 图标 + 文件名（`MimeUtils` 30+ 扩展名映射）
- 剪贴板面板：日期 + 文本行，点击复制；原版读 Smartisan 私有 `getCopyHistory()` → 我们自建历史

**v1 形态合成**：OS 8 骨架（标题条 + dock + 侧栏槽位）为主，OS 3.x 三面板挂接在 dock 左端三个固定按钮（相册/文件/剪贴板）——面板代码在 OS 8 APK 里仍存在（legacy），设置页的「边栏小工具」= OS 8 对顶栏的替代，归 v2。

## 权威数值

### 颜色（OS 8 APK 资源表，与设备密度无关）
| 资源 | 值 | 用途 |
|------|-----|------|
| `sidebar_root_background` / `disable_dim_color` | `#b3000000` | 侧栏条底色 |
| `dim_color` | `#66000000` | 全屏压暗 |
| `content_view_background_color` | `#40000000` | 内容面板底 |
| `topbar_text_color` | `#ccffffff` | dock/顶栏文字 |
| `topbar_text_dim_color` | `#5affffff` | dock 文字暗态 |
| `content_view_title_color` | `#9a000000` | 面板标题（浅色面板深色字） |
| `content_view_interval_time_color` | `#4d000000` | 面板日期分组文字 |
| `content_view_interval_more_color` | `#3fffffff` | 「更多」文字 |
| 拖拽时全屏 dim | `#b3000000`（官方 `updateDragWindow`） | 拖起侧栏条目时 |

### 尺寸
- 官方 OS 3.x：`sidebar_width` 48dp；exit/setting 按钮区高 89.3dp；图标 42.6dp；条目图标 32dp；行 padding 3dp；
  列表头部 divider 4dp 宽；`drag_scroll_view_top_area` 96dp / `bottom_area` 192dp（拖拽自动滚区）；
  `clip_radius` 6dp；`dragViewScale` 12dp；浮字 13dp 白字 `float_text_bg` 背景距条 12dp；FloatText 宽 ≤180dp、DragView 气泡宽 ≤159dp
- 垃圾桶（两套桶值）：`trash_width` 225 / `trash_height` 297 / `trash_react_width` 280 / `trash_react_height` 500 /
  `trash_display_height` 123 / `trash_float_up_height` 75
- OS 8 APK：`contact_avatar_size` 36dp（OS3.x 32dp）、`clip_radius` 6dp 同、`dragViewScale` 12dp 同、
  `drag_scroll_view_top/bottom_area` 90dp、`sidebar_width` 213px（密度桶相关，按 R2 实测 ≈66dp 取整）、
  `app_item_height` 134dp / `app_item_width` 90dp（应用选择页用）
- **尺寸基准以 R2 截图像素实测为准，其余用官方 dp 值**；R2 = 1080×2340，密度覆盖 400（1dp=2.5px）

### 动效（官方源码 `util/anim/`，OS 8 未偏离的假定）
- 侧栏进：横移滑入 150ms + fade 150ms `CUBIC_OUT`，条底 fade 200ms 延迟 100ms，阴影线 50ms
- 侧栏出：滑出 200ms `CUBIC_OUT` + fade
- 顶栏进：条目从 -height 落下 200ms `CUBIC_OUT` + fade，底 fade 200ms 延迟 120ms
- 内容面板：alpha 300ms `CUBIC_OUT`；清除 = 列表滑出 100ms + fade 200ms
- 拖拽换列：正常列表 scale 1→0.2 + fade 300ms，拖拽列延迟 75ms 滑入
- 加条目 pop：fade + scale 0.3→1.2→1（各 200ms，第二段延迟）
- 回弹 `DropAnim` 200ms（位置+缩放至 32/48=0.667）
- 垃圾桶：浮起/落下 100ms、吸入 200ms、摇晃 ±2px/±2° 70ms 步进循环（CIRC_IN_OUT）
- 缓动全部 Penner 系（QUAD/CIRC/CUBIC/QUART/QUINT × IN/OUT/IN_OUT），代码驱动无 XML anim
- R2 录屏编码帧率过低（0.37fps）无法测出 OS 8 实际时长，**以官方数值为准**

### 声音/震动
官方 app 源码与 OS 8 APK 均无声音资源（原版音效在 framework 侧）。v1 无声，震动 `VIBRATE` 权限备用。

## 拖拽协议（官方源码语义）

**外部拖入（DragEvent）**：`ACTION_DRAG_STARTED` → 条切换 dragged 形态 + FloatText 浮字随指；目标判定按 mime：
- 短信联系人：`text/plain` 或 `image/*` 1 个 → `ACTION_SENDTO smsto:` + `sms_body` / `ACTION_SEND` + `EXTRA_STREAM`
- 邮件联系人：任意 mime → `mailto:` + `EXTRA_TEXT` / `ACTION_SEND_MULTIPLE`
- 应用图标（ResolveInfoGroup 语义）：`ACTION_SEND(_MULTIPLE)` + setPackage 定向
- `PendingDragEventTask`：file:// 不存在时轮询 1s×20 次再投递（v2）
- 拖拽中列表自动滚：上 96dp 区向下、下 192dp 区向上，20ms tick，速度 `h/200 × (1+rate²×3)`

**侧栏拖出（长按）**：自绘 DragView 气泡 + 全屏 dim + 垃圾桶浮起；落垃圾桶 = 移除（确认弹窗），
落其他 = 回弹。数据拖出（照片/文件/文本）：平台 `startDragAndDrop` + `DRAG_FLAG_GLOBAL`。

## 数据管理器（官方源码语义 → Kotlin 重写）

| 管理器 | 官方实现 | 我们的 |
|--------|---------|--------|
| Apps | `queryIntentActivities(MAIN/LAUNCHER)`，SQLite weight 排序，上限 20，种子中国区清单，包监听增删 | 同，JSON 存 prefs（v1） |
| Contacts | 4 类（短信/邮件/微信/钉钉）各走系统 API，上限 10，头像存库 | **v2**（微信 shortcut 依赖 INSTALL_SHORTCUT 广播） |
| Photos | MediaStore Images 倒序，ContentObserver 仅激活期注册，日期桶 {0,1,7,30,MAX}，dng 排除，隐藏清单入 db | 同（Room 存隐藏） |
| Files | MediaStore + 录音 provider + 文件系统扫 TARGET_DIR（QQ/微信/钉钉/百度网盘/Download） | MediaStore + Download 目录扫（谨慎权限） |
| Clipboard | Smartisan 私有 `getCopyHistory()` | **自建**：大爆炸复制（同进程）+ 分享收纳 + 打开面板时 best-effort 读系统剪贴板 |
| ShareGroups | `queryIntentActivities(ACTION_SEND)` 按包分组，上限 20，黑名单 + 顺序偏好（微博/微信/QQ/邮件…） | 同，去掉 smartisan 包逻辑 |

## 设置项对照（R2 设置页全量，截图 r2_settings*.png）

| 原版设置项 | v1 对应 |
|-----------|---------|
| 一步总开关 + 说明文案 | ✅ OneStepPrefs |
| 边栏小工具（航班/火车/酒店/电影/屏幕使用） | v2 |
| 添加边栏联系人快捷方式 | v2 |
| 添加微信小程序快捷方式 | v2+ |
| 添加边栏应用快捷方式 | ✅ |
| 添加拖拽分享快捷方式 | ✅ |
| 快捷按键呼出一步（硬件键+音量下） | ❌ 磁贴替代 |
| 拇指下拉呼出一步（大面积触屏下拉） | ✅ evdev 版（原版手势） |
| 拇指下拉触发面积（5 档） | ✅ 同 5 档 |
| 讲解视频链接 | — |

## 验收红线（2026-11-15 触摸事故后新增）

**全屏悬浮窗用完即删，绝不允许残留。** 事故：内容面板 dismiss 只做了动画，真正摘窗写成了
`View.parent.removeView`（对窗口无效），每次开关面板泄漏一个透明全屏窗——叠到 9 层时
整屏触摸全部被吃 + 合成卡顿（用户手机一度完全无法操作）。

回归测试（装机后必跑，dedup 计数窗口 hash）：
```
侧栏开=3（标题条+侧栏+热区）→ 面板开=4 → 面板关=3 → 侧栏收=1（仅热区）
```
且侧栏收起后，自查窗口的 touchableRegion 不得出现全屏矩形。恢复手段：`am force-stop com.smartisanrevival.bang`。

教训：视觉验证（截图）看不出透明触摸层，**悬浮窗功能必须同时验证触摸穿透**。

## 活窗口收缩 · 无 root 判决（2026-11-15 实测）

用户指正：原版 OneStep 1.0 是「整个系统画面收缩到左下角」的**活窗口**（可操作）。在魅族 21 Pro
（Android 16 / Flyme，无 root）上四条路全部实测封死：

| 路线 | 结果 |
|------|------|
| `am stack resize <taskId> <bounds>` | Android 16 已删除该子命令 |
| `am start --windowingMode 5`（重定向已运行任务） | 无视，intent 直接投给现有全屏实例 |
| `settings put global enable_freeform_support 1` + freeform 启动 | Flyme 无视，仍全屏（已恢复该设置） |
| `performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)` | Flyme 无视，画面无变化 |

结论：真·活窗口收缩只有 **root / LSPosed 挂钩 framework** 这一条路（可行性的 D 级路线）。
无 root 下的形态取舍：OS 8 式覆盖侧栏（当前实现）为 v1 主线；静态截图卡片方案已被用户否决。
相关代码留位：`ShrinkCardView.kt`（未接线）、`OneStepAccessibilityService.takeScreenShotCompat()`。

**悬浮窗焦点铁律**：所有窗口必须 FLAG_NOT_FOCUSABLE。事故：常驻热区窗无此标志 → 抢走
`mCurrentFocus`/`imeInputTarget` → 用户键盘完全失灵。大爆炸面板窗（bang 侧）同样缺此标志，
测试时也会抢焦点，建议 bang 侧补上。

## 归档索引

- R2 截屏：`references/recordings/r2_*.png`（wake/home/触发/布局镜像/设置页全滚动）
- 录屏：`r2_trig_anim.mp4`（触发瞬间，帧率低仅作存在性证据）
- uidump：`r2_onestep_uidump.xml`（应用窗口自报全屏 → framework 裁剪证据）
- OS 8 APK 资源 dump：`references/smartisan-apk/sidebar_resources_dump.txt`
- 官方源码：`references/repos/packages_apps_OneStep/`（Apache-2.0，可重写移植）

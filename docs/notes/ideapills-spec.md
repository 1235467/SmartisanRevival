# 闪念胶囊 IdeaPills · 复刻规格（R2 实机取证 + 原版 APK 资源对表 + 社区实现调查）

> 2026-11-15 第 0 步产出。来源：坚果 R2（Smartisan OS 8 / Android 11）实机运行时取证；
> 原版 `com.smartisanos.ideapills` v7.2.0 APK 全量资源解析（permissions / 组件 / 458 条中文文案 / 颜色 / 尺寸 / 素材名）；
> 社区复刻调查（shengwuji / idea_pills / gavinliu-Capsule / IdeaMemo）。
>
> **许可证定档（先说红线）**：原版 IdeaPills 从未开源（GitHub issue #18 一直在求开源），
> 从 R2 拉的 APK 属**红档**——行为、数值、文案可对表，**代码与素材一行/一张都不能搬**。
> 社区里 shengwuji 是 Apache-2.0（绿档，逻辑可搬），`qiangxu/idea_pills` 无 LICENSE（红档，只读）。
> 本项目所有胶囊视觉必须**自绘**，官方 9-patch 只能当对照图。

## 一、原版架构（R2 实测）

| 项 | 事实 | 证据 |
|----|------|------|
| 包名/版本 | `com.smartisanos.ideapills` v7.2.0（R2 上 versionCode 30），priv-app | `pm path` → `/system/priv-app/IdeaPills/IdeaPills.apk` |
| 常驻方式 | `IdeaPillsService` 由 **system_server 绑定**（uid 1000），开机常驻 | `dumpsys activity services`：Client AppBindRecord = system |
| 悬浮窗 | 全屏窗，gravity TOP\|RIGHT，**ty=2063**（Smartisan 私有窗口类型，凌驾 APPLICATION_OVERLAY） | `dumpsys window windows` |
| 收起态触控区 | 右上角贴边 `(1040,240)-(1105,445)` @1080×2340 ≈ **26×82dp**，mHasSurface=false 时也保留窗 | 同上（触控区宽 65px≈26dp，高 205px≈82dp） |
| 触发 | 长按 Home 键 / 长按侧键（智慧键），framework 层拦截后广播 | APK 文案：`长按%s`、`闪念胶囊激活快捷键`、Home 重定义为短按亮屏/长按关机菜单 |
| 服务生命周期广播 | `ACTION_IDEAPILLS_STARTED / STOPED / DESTORYED` + `AlarmTask`（protected-broadcast） | manifest |
| 与一步的关系 | `com.smartisanos.sidebar.permission.IDEA_PILLS_SYNC`——一步侧栏读胶囊数据（ongoinglist） | manifest 权限 |
| 权限特征 | **无 RECORD_AUDIO**——录音走 `smartisanos.recorder.*` provider（framework 代录）；`INJECT_EVENTS`/`INTERNAL_SYSTEM_WINDOW`/`READ_FRAME_BUFFER` 全是系统特权 | badging |

**结论**：原版是特权系统组件，我们不可能照抄架构。可行形态 = 无障碍服务承载 `TYPE_ACCESSIBILITY_OVERLAY` 窗 + 前台服务录音 + Room 存储——与本项目既有 BigBang/OneStep 同栈。

## 二、原版功能全量拆解（APK 文案对表）

### 2.1 胶囊六分类（颜色即语义，资源名实锤）

| 颜色资源 | 值 | 语义（APK 文案） | 行为 |
|----------|-----|-----------------|------|
| `blue` | `#ff5c89f2` | **记事**（默认） | 普通胶囊 |
| `orange` | `#ffffa826` | **待办事项** | 带 checkbox（`bubble_todo_checkbox`「标记」），可「隐藏已完成/显示已完成」，完成后进「已处理」 |
| `green` | `#ffb6d92b` | **待发送信息** | 点击进短信发送（联动系统短信，v2 可做分享到 IM 替代） |
| `red` | `#ffed4e42` | **重要事项** | 高优先；配合提醒（日历）使用 |
| `purple` | `#ffa659de` | **灵感** | 普通胶囊+分类 |
| `text_share` | 白底蓝字 `#ff6890ed` | **共享**（欢喜云双人共享） | ❌ 弃（需要云端账号体系） |
| （时间胶囊） | 棕字 `#ff5c3c2a` | **时间胶囊**（自动收集微信/QQ/飞书/微博素材） | ❌ 弃（系统级监听，第三方做不了） |

- 语音胶囊内嵌播放按钮：**每个分类色各有一套** `*_pills_voices_btn_play_n/p`、`*_btn_stop_n/p`（蓝/绿/橙/紫/红/共享/时间 7 套）
- 分类筛选：`filtrate_check_blue/green/orange/purple/red/share/time`（列表页按类型筛选）

### 2.2 语音输入（原版行为）

- 按住说话 → 松手转文字；波形动画（`bubble_wave_*`，列表里语音条目高 32dp）
- 「没听清，请重新说话」（`recognize_no_result`）——识别失败不落库，气泡提示
- `play_voice`「播放语音」——原版把语音**保存**下来可回放（对应录音机 provider）
- `bubble_optimize_text`「联网后会优化输出文字」——离线引擎先出字，在线引擎二次优化
- 播放/停止按钮带 n/p 两态（normal/pressed）

### 2.3 胶囊操作（气泡菜单文案）

复制文字 / 编辑 / 分享 / 删除（确认弹窗：`确认要删除这个胶囊？`）/ 大爆炸（`bubble_boom`，胶囊内容直接炸词，两层爆炸要排队）/ 添加提醒（`bubble_calendar`，对接日历）/ 添加附件（`最多 %d 个附件，总大小不超过 30 MB`，图片/音频/视频/文档四类）/ 微信（`bubble_btn_weixin`）/ 收起所有 / 展开所有 / 按类型筛选 / 搜索（`搜索胶囊内容`）/ 拖拽保存（`成功保存到胶囊`）+ 垃圾桶删除区（`移出删除区域`）

### 2.4 提醒（日历联动）

`选择提醒日期` + 提前量档位：任务发生时 / 5 / 15 / 30 分钟 / 1 / 2 小时 / 1 / 2 天 / 1 周；`提醒时间不能早于当前时间`；通知栏「已设置提醒」图标。

### 2.5 无障碍朗读（原版做了完整的 TalkBack 支持）

`read_bubble_*` 系列：已删除 / 胶囊列表已显示(关闭) / 正在移动胶囊 / 已移动 / 移至第%d项 / 已移回原位 / 已分享。**我们复刻时朗读事件照做**（自己加 contentDescription 即可）。

### 2.6 明确不做（第三方做不了或价值低）

| 功能 | 原版依赖 | 决定 |
|------|---------|------|
| 共享胶囊（欢喜云双人协作） | 欢喜云账号体系 | ❌ |
| 时间胶囊（自动收集微信/QQ/飞书/微博） | framework 通知/内容监听特权 | ❌ |
| 锁屏钉住应用 | system 特权 | ❌ |
| 电话号码/快递/外卖智能识别（cootek/xy 系列） | 系统识别服务联动 | ❌ |
| 绿色胶囊「待发送信息」直接进短信编辑 | 联动系统短信 | v1 弃；v2 做「分享到 IM」 |
| 聊天宝分享 | 推广期残留 | ❌ |

## 三、权威数值（从原版资源表提取）

### 颜色

| 资源 | 值 | 用途 |
|------|-----|------|
| `blue/green/orange/purple/red` | 见 2.1 表 | 六分类胶囊底色 |
| `bubble_text_color` | `#ffffffff` | 胶囊内文字（白） |
| `bubble_date_text_color` | `#80ffffff` | 胶囊内时间戳（半透明白） |
| `bubble_hint_text_color` | `#66ffffff` | 胶囊内提示文字 |
| `bubble_text_color_share` | `#ff6890ed` | 共享胶囊文字（白底蓝字） |
| `dim_bg` | `#98000000` | 胶囊列表背后的全屏压暗 |
| `editor_text_color` | `#cc000000` / hint `#26000000` / label `#66000000` | 编辑态 |
| `drag_text_color` | `#ffffffff` + 阴影 `#33000000` | 拖拽气泡文字 |
| `pop_win_bg_color` | `#fffafafa` | 弹出菜单底 |

### 尺寸

| 资源 | 值 | 用途 |
|------|-----|------|
| `bubble_item_normal_height` | **44dp** | 单行胶囊高 |
| `bubble_item_text_size` | **13.5sp** | 胶囊文字 |
| `bubble_dragging_max_width` | **229dp** | 胶囊最大宽 |
| `bubble_item_normal_textwidth_min` | 60dp | 最短胶囊 |
| `bubble_item_large_text_height` | 134dp | 展开的多行文字胶囊 |
| `bubble_item_large_voice_height` | **184dp** | 语音胶囊展开高（含波形） |
| `bubble_list_item_wave_height` | 32dp | 列表内波形条高 |
| `bubble_head_setting_item_size` | 36dp | 列表头右上按钮（搜索/设置） |
| `bubble_head_search_width` | 247dp | 搜索框宽 |
| `bubble_or_board_titlebar_height` | 40dp | 列表标题条高 |
| `drag_layout_minwidth / maxwidth` | 114 / 150dp | 拖拽气泡宽 |
| `drag_text_size` | 13.5sp | 拖拽气泡文字 |
| `bubble_attachment_icon_size` | 40dp；附件图 80×80dp | 附件 |
| 收起态把手（R2 实测） | ≈26×82dp，右缘贴边，y≈240px 起 | 触控区 |

### 胶囊形态（素材解读）

- 单行：圆角胶囊 + **顶部中央小三角尾巴**（指向列表头/收起位方向）+ 白描边 ≈2dp + 底部细阴影；色底白字
- 两行/三行：同款形状加高（`bubble_drag_background_two/three_*`）
- 共享款：白底、蓝字、蓝描边
- 社区实现（shengwuji）用纯自绘 + 1dp 白描边达到同观感，验证了自绘可行
- ⚠️ 官方 9-patch 已解出存档（见附录），仅作对照，**禁止打包**

## 四、我们的 v1 规格

### 4.1 触发（三入口，全部复用既有能力）

| 入口 | 实现 | 备注 |
|------|------|------|
| **长按音量下键**（主入口，对齐原版"快捷键"心智） | 独立无障碍服务 `FLAG_REQUEST_FILTER_KEY_EVENTS`：短按 <600ms 消费后手动补 `adjustSuggestedStreamVolume`（照 idea_pills 方案，避免长按时音量狂掉），长按 → 唤起录音 | 声物记/idea_pills 双重验证过的方案；与 BigBang 的 evdev 观察型触发不冲突（一个消费按键、一个观察触摸） |
| 快捷设置磁贴 | 复用 `BangTileService` 模式新建「胶囊」磁贴 | 零权限成本 |
| 分享/选字收纳 | `ACTION_SEND` + `ACTION_PROCESS_TEXT` 新增「存到胶囊」目标（复用 OneStepSendActivity 模式） | 大爆炸选中后也要能「存到胶囊」 |

（原版"长按 Home/侧键"需要 framework 拦截，第三方无法做；音量下键是社区验证过的最优替代。）

### 4.2 录音链路（对齐 roadmap 技术栈：sherpa-onnx + Room）

1. **前台服务** `foregroundServiceType="microphone"`（Android 14 硬要求），常驻通知低调化
2. `AudioRecord` 16kHz 单声道 PCM 流（不用 MediaRecorder 先存后转——我们要实时波形 + 说完自动停）
3. 实时波形：PCM chunk 算 RMS → 自绘（对齐原版 32dp 波形条），sqrt 归一化（idea_pills 验证的曲线）
4. **说完自动停**（Silero VAD 状态机：说过话才计时静音，3/5/8s 档默认 3s；一次没说话永不自动停）——声物记方案，防空录音
5. 存档：PCM 转 AAC（MediaCodec）或直接 AAC 录一份双通道；胶囊保留音频可回放（对齐原版 play_voice）
6. **转写：sherpa-onnx**。模型决策点：
   - 声物记用 SenseVoice int8（质量好但 **229MB**，不适合随包）
   - 备选：Zipformer streaming 中文 int8 ≈ 70–80MB（roadmap 原方案）
   - 建议：**APK 不带模型，首次进胶囊功能时弹一次性下载（WiFi）**，下载失败可跳过——胶囊退化为纯文字模式不阻塞
7. 转写失败/为空 → 胶囊显示「语音 Ns」+ 播放按钮（对齐原版降级行为）；「没听清」提示复用原版文案
8. 两段式纠错（v2）：正则规则表 + 用户热词表（声物记方案，直接照抄思路）

### 4.3 悬浮层（挂 BigBang 无障碍服务）

- 窗口类型 `TYPE_ACCESSIBILITY_OVERLAY`（不需要悬浮窗权限，绕开 Flyme 两道门；声物记在小米上同样结论）
- 录音中：底部升起录音胶囊（时间正计时 + 波形 + 停止/取消），FLAG_NOT_FOCUSABLE + NOT_TOUCH_MODAL，不挡下层 App
- 展开：右缘把手（26×82dp，贴右上对齐原版 R2 实测位置）→ 点击/内滑展开胶囊面板（宽 ≈72% 屏宽，背后 `#98000000` 压暗），把手长按可上下拖动调位（声物记验证的刚需）
- 面板结构（对齐原版）：标题条（搜索 + 设置 36dp 按钮）+ 分类筛选 chips + 胶囊时间轴列表 + 底部「新建文字胶囊」
- **红线（承接 onestep-spec 验收教训）**：全屏面板窗口用完即删；每次开关面板后自查 `dumpsys window` 窗口数与 touchableRegion，透明全屏触摸层残留 = P0 事故（一步侧已踩过一次）
- 息屏即收（SCREEN_OFF receiver，防 AOD 残留）——声物记三轮踩坑后的定版语义

### 4.4 数据（Room）

```
capsule 表：
  id, createdAt, updatedAt
  type          — blue/orange/green/red/purple（+share 预留）
  content       — 文字（语音转写结果或手输）
  audioPath     — 录音文件（可空）
  durationMs    — 录音时长
  done          — 待办勾选（orange 专用）
  doneAt        — 完成时间（进"已处理"）
  remindAt      — 提醒时间（可空，v1.5 接日历/闹钟）
  colorIndex/label — v1 用 type 即可
  deleted/deletedAt — 回收站（30 天）
```

音频文件存 `filesDir/capsules/`。列表按日期分组（今天/昨天/更早，格式复用原版 `今天`/`明天 HH:mm` 文案体系）。

### 4.5 胶囊操作（v1 全做）

点选展开（单行 → 展开多行编辑）· 编辑文字 · 复制 · 分享 · 删除（确认弹窗 + 拖到垃圾桶区删除）· 收起所有/展开所有 · 按类型筛选 · 搜索 · 待办勾选 + 「隐藏已完成」· 「已处理」分区（toast_for_bubble_receive_by_app 文案语义）
v1.5 追加：大爆炸联动（胶囊内容炸词 / 大爆炸选中存到胶囊）· 附件 · 提醒 · 导出 Markdown
v2 追加：绿色「待发送信息」→ 分享到 IM · 热词纠错 · WebDAV 备份

### 4.6 声音/触感

原版音效在 framework 侧拿不到。v1：开始录音一记短震（50ms）、完成转写一记 tick；不做提示音。

## 五、分期与验收（对齐 roadmap 第 3 期 2 周）

### M3a · 核心闭环（第 1 周）

- 音量下长按唤起 → 录音胶囊升起（**≤300ms**，roadmap 硬指标）→ 说完自动停/手动停 → 转写 → 胶囊落库 → 面板时间轴可见、可播、可编辑
- 磁贴 + 分享收纳两入口
- 模型一次性下载流程
- **验收**：长按到胶囊升起 <300ms；10s 语音转写 <2s（在线出字可放宽到 4s）；连续 50 条不崩不丢（roadmap 硬指标）；录音中息屏不崩、亮屏录音仍在

### M3b · 完整形态（第 2 周）

- 六分类颜色 + 待办 checkbox + 已完成隐藏/已处理分区 + 筛选/搜索
- 把手拖动调位、面板窗口纪律（开关后窗口数回归断言）
- 导出、提醒（本地通知兜底，日历 v2）
- **验收**：全部悬浮窗操作后 `dumpsys window` 无残留全屏触摸层；Flyme 后台录音存活（保活白名单引导文案）；无障碍朗读事件齐全

## 六、风险与已验证的坑（社区踩坑记录，直接继承）

| # | 坑 | 对策 |
|---|-----|------|
| 1 | Android 14 FGS 必须声明 microphone 类型，否则启动即崩 | manifest + 运行时双写 |
| 2 | Flyme 省电杀后台录音 | 保活引导四件套（与 docs/01 门 3 同源）+ 录音失败重试提示 |
| 3 | 音量键被消费后短按没声音 | 消费后手动 `adjustSuggestedStreamVolume`（idea_pills 方案）；音量条走 FLAG_SHOW_UI |
| 4 | 转写阻塞 UI | sherpa-onnx JNI 调用固定放独立线程（Kotlin 无 isolate 问题，比 Flutter 简单）；模型加载后台预热（BigBang jieba 词典预热的既有经验） |
| 5 | 模型 229MB vs 80MB | APK 不带模型；下载失败降级纯文字胶囊，不阻塞主功能 |
| 6 | 息屏 AOD 残留把手 | SCREEN_OFF receiver 收窗，亮屏恢复 |
| 7 | 透明全屏层泄漏（一步侧 P0 事故） | 面板关闭动画结束回调里 `removeView` + 窗口数回归断言 |
| 8 | 长按期间系统弹音量条/截图快捷手势 | 音量下长按在 Flyme 上还要验证：录屏快捷手势（音量下+电源）误触面；真机第 0 天先验证 |
| 9 | 无障碍服务被 Flyme 重置（本项目已知） | 复用 BigBang 引擎的自愈重试逻辑 |
| 10 | VAD 误停（用户还在组织语言） | "说过话才开始计静音" 状态机（声物记定版语义） |

## 七、第 0 天真机验证清单（开工前在 R2 + 魅族各跑一遍）

1. R2 上实际触发原版胶囊（长按侧键），录屏存 `references/recordings/r2_pill_*.mp4`——把动效（升起/收起/展开）逐帧看一遍，数值以录屏为准校准本规格
2. 魅族：长按音量下键 600ms 消费方案手感（短按补音量是否自然、是否误触录屏手势）
3. 魅族：AudioRecord 16k 后台录音 30s 穿越息屏是否被杀
4. sherpa-onnx Zipformer 中文模型在骁龙 8 Gen 3 上 10s 音频的转写耗时（验收指标 <2s 的依据）
5. `TYPE_ACCESSIBILITY_OVERLAY` 在 Flyme 全屏视频/游戏上层的显示与触摸行为

## 八、归档索引

- 原版 APK（R2 pull）：`references/smartisan-apk/ideapills.apk`
- manifest / 资源表 dump：`references/smartisan-apk/ideapills_manifest.txt`、`ideapills_resources.txt`
- 中文文案全集（458 条）：`references/smartisan-apk/ideapills_strings_zh_clean.txt`
- 颜色/尺寸：`references/smartisan-apk/ideapills_colors_dimens.txt`
- 素材名分组：`references/smartisan-apk/ideapills_drawables.txt`
- 已解出素材（仅对照，禁止打包）：`references/smartisan-apk/ideapills-extracted/`（21 张 9-patch 胶囊 + 32 张语音播放按钮等）
- 社区参考（已克隆）：`references/repos/shengwuji`（Apache-2.0，重点读 `docs/architecture/floating-window.md`、`speech-recognition.md`）、`references/repos/idea_pills`（无 LICENSE，只读逻辑）、`references/repos/Capsule`（空壳，无用）
- NLP 后端 APK（原版一步/胶囊共用）：`references/smartisan-apk/IntelligenWords.apk`（`com.smartisanos.intelligenwords`，内含 onestep 实体识别模型 + 分词配置，仅存档）
- R2 运行时取证（2026-11-15）：`IdeaPillsService` 绑定记录、`IdeaPills` 窗口 ty=2063 / touchable region 数据见本文第一节（未存 dump 文件，需要时重新 dumpsys）

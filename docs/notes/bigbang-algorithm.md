> 注：本笔记引用的 `references/smartisan-apk/`、`references/recordings/` 素材（原版 APK 解包与真机录屏）仅存本地，不随公开仓库分发。
# 大爆炸官方实现笔记（读源码 + R2 实机解包）

> 来源：`references/repos/packages_apps_BigBang`（官方 Apache-2.0）+
> `references/smartisan-apk/TextBoom_2023-01-16.apk`（坚果 R2 / Smartisan OS 8 实机 APK）。
> 本文档 = 重写 Kotlin 版时的"算法与数值依据"。

---

## 一、架构真相（重要的意外发现）

**官方 BigBang 是"显示端"，不是"触发端"。**

```
Smartisan framework（闭源，不在本仓库）
  → 长按判定 / 取字 / 按下坐标
  → Intent(EXTRA_TEXT=text, boom_index=按下字符index, boom_startx, boom_starty, boom_image=图片OCR标记)
  → BoomActivity（开源，本仓库）
      → 【在线分词】POST 到锤子服务器 Constant.SEGMENT_URL（服务器已死！）
      → 返回 int[] 分词区间
      → BoomChipPage 渲染词块
```

三个直接推论：
1. 官方仓库**编译不出能用的 APK**（分词依赖已死的在线服务）——文档 09 的判断正确。
2. 我们要自己补的正是 framework 那一半：**触发 + 取字**（无障碍/截图方案，见 docs/02）。
3. 分词我们用本地 jieba，**比官方当年还强**（官方要联网，我们离线）。

## 二、数据模型（BoomWordsLayout）

```java
Word { word: String, start: Int /*在原文中的字符起点*/, punc: Boolean }
```

- 分词结果 `int[]` = `[词1起, 词1止, 词2起, 词2止, ..., 标点1起, 标点1止, ...]`
  （词在前、标点在后两段；`layoutWords()` 会重建合并成单一文本流）
- **标点是独立词块**：`punc=true`，用窄块样式（`punc_min_width=21dp, punc_base_width=8dp`）
- 词块宽度 = `max(minWidth, baseWidth + 文本实测宽度)`：
  - `word_min_width=33dp`，`word_base_width=18dp`（≈左右各 9dp 内边距）
  - `punc_min_width=21dp`，`punc_base_width=8dp`
  - 文本测量用实际渲染 TextView 的 Paint（15dp 字号）
- 文本清洗：全角空格 `\p{Z}` → 半角空格后 trim；空块丢弃
- **行装箱：贪心**。页宽 = 屏宽 − 17dp − 16dp；一行放不下就换行；单块超行宽也独立成行
- 被按中的字符 index（boom_index）映射到对应词块 → `mTouchedIndex`，行数超限时**以该行为中心**取前后窗口

## 三、动效参数（BoomAnimator 官方数值）

| 项 | 值 | 说明 |
|----|----|------|
| 炸开 BOOM_DURATION | **200ms** | chip: scale 0→1 + alpha 0→1 + 平移→0，**DecelerateInterpolator(1.5)** |
| 渐变 FADE_DURATION | 200ms | 页面整体淡入 |
| 移动 MOVE_DURATION | 200ms | 行位移/选择条出现（高度+透明度并行） |
| 隐藏 HIDE_DURATION | 100ms | 选择条消失 |
| 退出（OS8 activity_exit） | 300ms | 整页下滑出屏（accelerate） |
| 弹出（OS8 popup_enter） | 200ms | 上滑入场 |

**官方"炸开"不是飞散**：chip 在自己的网格位置上 scale 0→1 弹出。
"从原文字位置飞散"发生在 framework 把 boom_startx/y 传入后页面定位时（整体）。
→ 我们复刻时保留 docs/02 的"从原位飞散"设计（那是 v0 无截图下的伪原位），v1 有 OCR 真实 bbox 后
可以做成官方网格弹出 + 原位对齐混合。

## 四、选择/多选语义（BoomActionHandler + SwipeSelectView）

- 选择集合 = `TreeSet<Integer>`（chip 索引，天然有序）
- **划选（swipe select）**：按下 chip A → 反选其状态 → 手指滑动，途经的 chip 全部并入
  [min(A,当前), max(A,当前)] 区间 → 松手结束。边缘 100dp/165dp 触发自动滚动（25ms 一帧）
- **选择条（select bar）**：选中后顶/底出现操作条；**选区所在行整体上下分开让位**：
  - 上方行上移 `-30dp`（chip_row_move_up_offset）
  - 下方行下移 `+18dp`（chip_row_move_down_offset）
  - 这就是"选中后文字自动让出空间"的招牌手感，200ms MOVE_DURATION
  - **注意：只是 `translationY`，行内词块不重新排布，操作按钮是浮层。** 照抄时别把
    "让位"实现成布局重排（改 padding / 插视图），那会让字真的跳位置
- 取消选择：点空白 → 清空 → 行回位（handleClick: 有选中先清选区，无选中才退出页面）
- 拖词进一步侧栏：侧栏在显示时，长按（系统 LongPressTimeout）chip → `SidebarUtils.dragText()`（OneStep 联动，framework 私有 API，我们用 ClipData/DragEvent 替代）

### 炸开只动前 N 行

`BoomChipPage.mDoBoomAnimation`：`animationRows = Math.min(mLayout.getRowCount(), 12)`，
只有前 12 行逐块飞入，其余行直接就位。长文整屏同时炸开会糊。

## 五、OS 8（R2 实机）素材清单 → `references/smartisan-apk/textboom-extracted/`

| 素材 | 文件 | 用途 |
|------|------|------|
| 炸词音效 | `ocr.ogg`（**0.494s**，2016 原版至今未变） | 炸开瞬间播放 |
| 词块正常态 | `chips/boom_chips_bg_normal.9.png` | 白底圆角 + 细描边（纸片） |
| 词块选中态 | `chips/boom_chips_bg_pressed.9.png` | **实心蓝**圆角（锤子蓝） |
| 标点块 | `chips/boom_chips_punctuate_*.9.png` | 透明底窄块 |
| 微信专用块 | `chips/boom_chips_bg_*_wechat.9.png` | 深色模式/微信场景专用 |
| 拖拽光标 | `chips/boom_chips_cursor_enter/delete.png` | 拖动合并时的插入/删除指示 |
| 背景纹理 | `chips/boom_bg_texture.png` | 暗背景上的噪点纹理 |
| 全选符号/空格 | `chips/boom_chips_all_sym/space.png` | 顶栏扩展操作 |
| 炸图按钮 | `chips/boom_area_button_*.9.png` | 图片炸词 UI |

另发现（OS 8 加的，跟复刻词块层无关但记录）：
- `assets/tt_general_ocr_v1.0.model`：内置 OCR 模型（字节数系），官方 OS8 已带离线 OCR
- `liblens.so` + aiui（科大讯飞）配置：视觉搜索 + 语音助手整合
- `res/anim/double_boom_*.xml`：双指炸（OS6.6+）的出入场

## 六、对我们 v0/v1 的直接结论

1. **词块宽度公式、贪心装箱、标点独立块** → 照抄官方（Apache-2.0，可搬）
2. **选中行让位 -30/+18dp** → 必做，这是"锤子味"核心动效
3. **DecelerateInterpolator(1.5)、200ms 系** → 起步参数，再与 R2 录屏逐帧校准
4. **音效直接用 ocr.ogg**（0.494s）——自用复刻无分发问题，开源则需替换或注明
5. 微信深色块、拖拽光标等贴图可直接采样配色/形状，用 Compose 重绘（不搬 PNG，统一设计系统）
6. 官方"在线分词"已死 → 本地 jieba-android（docs/09 已选型）
7. 触发端（framework 的活）= 我们的无障碍方案，见 docs/02 §二

## 七、真机触发端实测（Meizu 21 Pro / Flyme / Android 16）

framework 那半边我们没有，只能用 Shizuku 读输入层。踩过的坑记在这里：

| 坑 | 现象 | 正确做法 |
|----|------|---------|
| 用 `getevent -ql` 读触摸 | 进程在跑，一条日志都没有 | 文本输出经管道**整块缓冲**，实机读不到实时行。改为在 shell 进程里直接 `FileInputStream(/dev/input/eventN)` 读二进制 |
| 按文本解析 `DOWN`/`UP` | 永远不触发 | `-ql` 模式的值是十六进制数字，不是 `DOWN`/`UP`。二进制帧更直接：`type/code/value` |
| 找触摸设备按名字猜 | 可能绑到指纹/手写笔节点 | 用 `getevent -pl` 的 `ABS_MT_POSITION_X/Y max` 判断，名字只作辅助 |
| 只在事件到达时判断按住时长 | 真人按住不动就不触发 | 手指静止时内核**不再上报事件**，必须在下按那一刻 `postDelayed` 起定时器主动检查 |
| 按下瞬间就记起点 | 位移判定永远失败 | 触摸屏先报 `ABS_MT_TRACKING_ID` 再报坐标，起点要等该帧 `SYN_REPORT` 再取 |
| 跨线程读位置 | 偶发漏判 | 读取线程写、主线程定时器读，字段要 `@Volatile` |
| `input motionevent UP` 想取消 App 长按 | 无效 | 注入走虚拟输入设备，和真实触摸不是同一条流。当前做不到，面板直接盖上去 |

本机数据：触摸屏 `main_touch` = `/dev/input/event7`，原始坐标 13680×31920（屏幕 1368×3192 的 10 倍），
长按阈值取 `ViewConfiguration.getLongPressTimeout() - 40` ≈ 360ms。

**重启后必做的两件事**（漏了就是"点了没反应"）：

1. Shizuku 服务端重启后不会自启，要用 `libshizuku.so` 重新拉起来
2. Flyme 会把 `enabled_accessibility_services` 重置成 `null`

顺序：先 Shizuku，后无障碍。反过来的话服务连接时授权检查失败。
现在的实现加了自动重试（3 秒一次，40 次），所以顺序反了也能自己等到。

**Flyme 会重置无障碍开关**（`enabled_accessibility_services` 变 `null`）。重装/重启后按压没反应，
先查这个设置项。

## 八、待办

- [ ] R2 录屏逐帧校准：炸开实测时长 / 选择条让位动画 / 收招
- [ ] BoomOcrActivity（炸图流程）细读——v1 OCR 管线参考
- [ ] framework 触发端逻辑：如需深挖，clone `android_frameworks_smartisanos-base`（大仓库，按需）

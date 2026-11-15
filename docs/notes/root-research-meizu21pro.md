# 魅族 21 Pro (M2481) Root / BL 解锁研究

> 2026-11-15 · 目的：为一步（窗口收缩、framework 注入）和大爆炸解锁 root 级能力。
> 本机：Meizu 21 Pro，adb 序列号 <你的设备序列号>，内核 `6.1.25-android14-11-maybe-dirty`（GhostLock 官方支持串）。

## 结论总览

| 路线 | BL 要求 | 持久性 | 现状 | 风险 |
|------|--------|--------|------|------|
| ① GhostLock 临时 root（CVE-2026-43499） | **不需要** | 重启失效 | **官方支持 21/21Pro 内核串，立即可试** | exploit 竞态失败→重启；不写分区不清数据 |
| ② 官方 BL 解锁 | — | — | **未找到官方通道**（flyme.com 无 unlock 页；mroot.flyme.cn 是 Flyme 6 时代在线 root，AIOS 机型名单未证实） | — |
| ③ GKI 内核刷入（FinaSU+KernelSU 持久 root） | **需要 BL 已解锁**（fastboot flash 被锁时拒绝） | 持久 | 构建流水线全绿（run#24/run#14），**真机刷入验证未做** | 刷错槽/AVB 问题→跳 fastboot（有切槽回退）；「21 与 21Pro boot 头逐字段相同，拿错模板=刷死」 |

## ① GhostLock 临时 root（推荐起步）

- 上游 App：https://github.com/YuKongA/ghostlock-app （3000★，Apache-2.0）
- **官方 SUPPORTED_DEVICES 明确列出**：`6.1.25-android14-11-maybe-dirty` → MEIZU 21 / MEIZU 21 Pro
- 用法：装 GhostLock APK + 任一 root 管理器（KernelSU/SukiSU Ultra 等，需带 libksud.so）→ App 内点 Run → uid 0 + ksud late-load
- 参考 Repo（魅族 21 的完整脚本版）：https://github.com/ymh001/meizu21-ghostlock-root （M2461 专用 offsets，**不可**直接用于 21 Pro；21 Pro 用 App 内置 profile）
- 重启后失效，需重新执行；`ksud soft-reboot` 不丢 root
- **对本项目的意义**：root 后可调 WindowManagerService binder 接口做真窗口缩放
  （`am stack resize` 在 A16 被删，但 root service call / 隐藏 API 通路可用）——
  「画面收缩到左下角」的活窗口方案从 D 级升为可实施。

## ② 官方 BL 解锁：未证实

- flyme.com 无 unlock 入口；open.flyme.cn 纯开发者服务
- mroot.flyme.cn（官网 Root权限页）：流程为「装授权应用→填信息→审核→激活」，
  提到「部分机型支持在线Root，逐步开放更多机型」，**未列 21 Pro**；
  另提到「设置-指纹与安全 中开启Root权限」（Flyme 传统内置 root，AIOS 大概率已移除，**待真机设置里搜「Root」确认**）
- 社区：21 Pro 无公开 BL 解锁方案。Note 16 (M521Q) 的 BROM 漏洞路线
  （CVE-2022-38694，github.com/Dingjinjin-chihiro/meizu-note16-unlock）仅适用展锐平台，
  21 Pro 是骁龙 8 Gen 3 (SM8650)，**不适用**。

## ③ GKI 持久 root（等 BL 有解再动）

- Repo：https://github.com/FengFaQ/MEIZU21-21Pro-GKI
- 产物：替换 boot 分区的 FinaSU+SUSFS 内核，系统仍是原厂 Flyme；release `meizu21pro-latest`
- 铁律（照抄其 MEMORY.md，违反必 brick 开不了机）：
  - GKI 固定 android14-6.1 / sub_level 25 / os_patch 2023-10，不许跳版本
  - 内核 release 串必须逐字符等于 `6.1.25-android14-11-maybe-dirty`
  - boot.img 必须带 AVB0+AVBf（魅族 ABL 即使 BL 解锁也校验）
  - **只刷非活动槽**，留回退槽；起不来 `fastboot --set-active=a` 10 秒切回
- 状态：CI 门禁全绿但**真机刷入验证未做**（HANDOFF.md 明示）——世界上还没有已验证的刷入成功案例

## 行动顺序

1. 真机设置里搜「Root」确认 Flyme 内置开关是否还在（零成本）
2. 装 GhostLock APK + SukiSU Ultra → 跑临时 root（最坏情况重启）
3. root 拿到后先验证：`service call window` 能否缩放窗口 → 能则启动「真收缩窗口」施工
4. BL 解锁持续蹲守：魅族社区/酷安关注官方工具动静；有通道后再评估 GKI 持久路线

## 触发失效排查备忘（2026-11-15 用户报「右上角内划无反应」）

- 热区实测 bounds x∈[1298,1368]（28dp≈1cm 宽），y∈[158,858]——起点略偏即落空，物理限制
- 正确手势：起点贴**右上角状态栏正下方**，向左水平内划 ≥24dp
- 原版手势走拇指下拉（设置页 ⑨，Shizuku 链路，真机校准待做）
- 手机重连后待办：真机复测热区 + 拇指下拉校准

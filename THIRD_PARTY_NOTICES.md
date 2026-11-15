# Third-Party Notices

本仓库引用的第三方组件与来源。

## 运行时依赖

| 组件 | 用途 | 许可证 | 来源 |
|------|------|--------|------|
| AndroidX Core | FileProvider（内置 Shizuku 安装） | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |
| Shizuku API (`dev.rikka.shizuku:api/provider` 13.1.5) | 免 root 读触摸事件、跑 shell 服务 | Apache-2.0 | https://github.com/RikkaApps/Shizuku |
| jieba-analysis (`com.huaban:jieba-analysis` 1.0.2) | 中文分词（大爆炸切词） | Apache-2.0 | https://github.com/huaban/jieba-analysis |
| rapidocr4j-android (`io.github.hzkitty:rapidocr4j-android` 1.0.0) | 离线截图 OCR（炸图片兜底） | Apache-2.0 | https://github.com/hzkitty/RapidOCR4j-Android |
| `shizuku.apk`（`android/app/src/main/assets/`） | 内置安装的 Shizuku 客户端，来自官方 Release，未修改 | Apache-2.0 | https://github.com/RikkaApps/Shizuku/releases |

## 参考与致谢（未复制其代码）

- [SmartisanTech/packages_apps_BigBang](https://github.com/SmartisanTech/packages_apps_BigBang)（Apache-2.0）—— 官方大爆炸实现，算法（切词、bounding box、多选）按其思路用 Kotlin 重写。
- [baoyongzhang/BigBang](https://github.com/baoyongzhang/BigBang)（MIT）—— 交互链路参考。
- [SmartisanTech/packages_apps_OneStep](https://github.com/SmartisanTech/packages_apps_OneStep) —— 仅参考行为逻辑，未复制代码（许可证不明）。

## 商标

Smartisan、锤子、坚果、BigBang（大爆炸）、OneStep（一步）等名称与商标归其权利人所有。
本项目为非官方复刻，仅作学习与个人使用，与 Smartisan / 字节跳动无隶属或背书关系。

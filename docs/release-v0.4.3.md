# VaultBridge Android 0.4.3 预发布

版本日期：2026-10-14。Android 0.4.3 / 版本码 9；Windows 保持 0.4.2 原代码和程序产物。本次只发布 Android 新包，不提供 Desktop 0.4.3。

**适用于 Android 8.0（API 26）及以上的手机和平板；鸿蒙设备需支持安装 Android APK。** 手机和平板使用同一个 APK。

## 下载与升级

- [Android APK](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/VaultBridge-Android-0.4.3.apk)
- [Android 完整包](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/VaultBridge-Android-0.4.3-local.zip)：APK、指南、文件/预览/更新/验收说明、MIT 与第三方许可证。
- [SHA256SUMS.txt](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/SHA256SUMS.txt)：以上两个资产的 SHA-256。
- 电脑继续使用 [Windows 0.4.2 便携版](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/VaultBridge-Desktop-0.4.2-portable.zip)。

Android 0.4.2 可通过应用更新获取本版；旧 0.4.0 / 0.4.1 没有入口，可以直接手动覆盖安装本版，不先卸载。安装来源权限和最终覆盖安装由系统要求你确认。Windows 更新器会跳过本次缺少对应平台资产的 Release，不需要把程序改名或重新安装。

## 新增功能

- Android“文件”页提供最近接收、最近查看、文件名搜索与类型筛选；原收发入口名为“文件传输”。
- 图片、PDF、文本与 Markdown 源码离线只读预览，Office/压缩包等交其他应用临时只读打开，不内置 Office 引擎或 WebView。
- 只索引本应用普通接收完成项，排除 Obsidian 同步、发送来源与暂存文件；记录实际发布后的 URI，旧完成记录在原授权位置按需核对最终文件后回填。
- 大文件按预览规则限量读取与渲染，不修改原件、不执行脚本/远程内容、不自动解压或安装。上限和权限细则见 [file-library.md](file-library.md)。

## 验证与边界

Android 0.4.3 / 版本码 9 本机构建、v2/v3 签名验证通过，保持原签名证书。实际 Java 互传 29 项、TLS 5 项、Android 核心 37 项、目录 39 项、SAF 发布模拟 33 项、更新 60 项、最近文件 37 项、预览规则 45 项、外观解析 21 项及 UiTheme 28 项通过。原 Windows 0.4.2 EXE 本轮核心回归 137 项通过，程序字节未变；Windows UI 未改，沿用此前 126 项记录，未重复运行。详细边界和原自动执行环境 HTTPS 失败说明保留在 [testing.md](testing.md)，不以规则测试替代真实下载、切换或预览显示。

真实设备上的 SAF、图片/PDF 渲染、手势、系统安装与其他应用选择器仍需验收。Huawei MatePad Air 12 / HarmonyOS 4.2 是重点验收设备之一；验收范围不代表应用仅适用于该设备。不能承诺所有编码/提供器或机型均已实测。操作和人工清单见 [file-library.md](file-library.md)、[acceptance.md](acceptance.md)。历史构建、MIT/Nitro 和签名规则保留；不收费、不增加云端笔记存储。

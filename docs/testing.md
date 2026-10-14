# 验证状态

## 0.4.2 应用内更新与称呼适配

版本日期：2026-10-14。两端 0.4.2、Android 版本码 8；0.4.1 本地文案修正并入本版，不单独发布 Release。Windows 与 Android 本机构建通过，当前自动回归结果如下，不沿用历史版本的结果或文件哈希充当本版验证。

发布后联网核验：实际 Android 更新客户端从公开 GitHub 选中并下载 0.4.2，完整 SHA-256 与发行 APK 一致。Windows 在本次自动执行环境建立 HTTPS 时返回 `SEC_E_NO_CREDENTIALS`，尚未收到 HTTP 响应；最简 .NET 请求和原生 WinHTTP 对照也失败，未发现是本应用请求封装导致的证据。Windows 真实下载与正常桌面启动后的切换仍待验收，不能用下列规则回归代替；可从 Release 页面手动下载便携包。

| 检查 | 结果和边界 |
| --- | --- |
| Windows 核心、传输与更新 | 137 项通过：原核心与传输 74 项，新增更新规则 63 项。 |
| 实际 Java 传输 / 原有 TLS | 29 / 5 项通过，使用生成文件与 Windows 回环服务。 |
| Android 核心 / 目录 / SAF 发布 | 37 / 39 / 28 项通过；SAF 为模拟文件提供器，不代替真实目录授权与操作。 |
| Android 更新 | 60 项通过，纯 Java 更新规则与客户端使用合成数据检查，不启动真实安装器。 |
| Android 外观解析 / UiTheme | 21 / 28 项通过，配色代码检查不代替原生显示与触摸验收。 |
| APK 构建与签名 | Android SDK 编译、DEX、资源、对齐及签名验证通过，版本 0.4.2 / 版本码 8；证书与旧版一致。 |
| Windows 原生 UI | 126 项通过，含更新提示、顶部布局及五档 DPI 深浅色边界检查。本机实际 175% DPI，其他比例为模拟；不代替实际跨显示器或触控验收。 |

更新回归覆盖数字版本和 Release/平台资产筛选、URL/重定向边界、GitHub digest 缺失/错误、大小与下载校验、Windows ZIP 路径/限额及 EXE/config 完整性等规则。Android 原生更新 UI/Provider 通过 SDK 编译，安装身份核对和真实系统交互仍需设备验收；预览/自测不自动访问 GitHub。

0.4.2 APK 签名证书 SHA-256：`c73736bc5a97a8030b597c004b494613ef221b86bb9a9ec228a71550e3837be7`，已核对与旧版一致。分发资产哈希见 [v0.4.2 SHA256SUMS.txt](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/SHA256SUMS.txt)。

旧 0.4.0 / Android 0.4.1 需手动安装一次本版，之后才有应用内更新入口。实际手机系统安装、HarmonyOS 来源权限、Windows 运行中停止分享并切换、SAF/微信/扫码和目标平板显示仍待人工验收。说明见 [updates.md](updates.md)、[release-v0.4.2.md](release-v0.4.2.md)与 [acceptance.md](acceptance.md)。

## Android 0.4.1 本地称呼修正

2026-10-14：手机/平板静态称呼按完整屏幕短边 dp 判断，布局与传输规则不变。Native SDK 构建、DEX/资源/对齐、Manifest 0.4.1/版本码 7、v2/v3 签名通过；证书与旧版一致。原 0.4.0 APK 和桌面字节未变，0.4.1 未单独公开发布，后续合入 0.4.2。

`scripts/test.ps1 -SkipDesktopUi` 通过：Windows 核心 74、实际 Java 传输 29、原 TLS 5、Android 核心 37、目录状态 39、SAF 发布模拟 28、外观解析 21、UiTheme 28。用户截图反馈旧版手机主屏 UI 正常；新版称呼及完整手机/平板行为仍待安装后确认。

APK SHA-256：`748cdf5a868e67608219f915ef975170c8ab55afc55b9b9a0b358f258fbb4d56`。

## 0.4.0 已公开预发布

构建与回归日期：2026-10-12；预发布日期：2026-10-13。0.4.0 为预发布版本，已完成本机 Windows/Android 编译与以下自动回归。三个 Release 资产的 SHA-256 见 [v0.4.0 校验文件](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.0/SHA256SUMS.txt)；0.3.4 的历史记录保留在下文。

| 检查 | 结果和边界 |
| --- | --- |
| Windows 核心与普通传输 | 74 项通过，含原有 36 项，以及范围、路径、权限、分块、持久状态、批准/拒绝、当前主库保护与同名另存等 38 项；只使用生成数据。 |
| Android 核心 | 37 项通过，运行实际核心 Java 代码。 |
| 原有 Windows/Android TLS | 5 项通过，实际 JVM 客户端与 Windows 回环服务互通。 |
| 双向分块传输 | 29 项通过，实际 Java 传输客户端与 Windows TLS 服务；含 270 MiB 文件、暂停/恢复、重启状态、笔记获取/回传、块校验、无扩展名文件另存及人为切断 TLS 测试连接后的自动续传。 |
| Android 接收暂存与发布 | 28 项通过，运行实际暂存发布代码与模拟文件提供器，覆盖暂停/失败保留、重启恢复、长度/哈希校验、重命名能力和同名保护；不代替真实 MatePad SAF 验收。 |
| 库位置与目录状态 | 39 项通过，模拟文件提供器，不代替真实 SAF 授权/移动。 |
| Android 外观解析 | 21 项通过。 |
| Android UiTheme 配色 | 28 项通过；实际配色代码检查，不是原生渲染。 |
| Windows 原生外观 | 107 项通过，含独立普通文件导航。实际本机 175% DPI，其余缩放比例是模拟；不代替实际触控板快速滑动或跨显示器验收。 |
| APK 构建与签名 | Native Android SDK 编译、DEX、资源与对齐成功；版本 0.4.0/版本码 6，v2/v3 签名验证成功。 |

回归已覆盖接收路径保护、SAF 暂存发布和任务列表滚动修改，当前两端源码已完成本机编译。0.4.0 安装包与签名证书的核对结果如下；ZIP 资产的哈希以本次 Release 校验文件为准。

0.4.0 最终 APK SHA-256：`bed79a6677883d5b4ac470c778c497d8066891c2f4d6a6b2ef82ea22e562c530`。Windows EXE：`70a367f9f9db4362349bed5534c1db4b05912af13206c7d3857862a378ded917`；同名 DPI 配置：`2ea4c99e3c0a2f7fa50d84dd67d47ee165fc3243a0e526b83724d9b29106442a`。新旧 APK 证书指纹均为 `c73736bc5a97a8030b597c004b494613ef221b86bb9a9ec228a71550e3837be7`，已核对一致；原 0.3.4 的 20 个本地文件哈希保持不变。

8 GiB 单文件和 64 GiB 单批是协议与边界检查上限，270 MiB 文件成功不能证明已传满全部上限。测试使用回环网络和生成文件，不读取真实笔记，不证明校园 Wi-Fi、MatePad 存储速度或文件提供器的大文件能力。

没有 Android 模拟器或连接的目标平板。MatePad 的真实 SAF、微信入口、相机、原生显示/快速滑动、应用退出后的行为和 Obsidian 刷新仍按 [acceptance.md](acceptance.md)验收。功能规则见 [file-transfer.md](file-transfer.md)，预发布说明见 [release-v0.4.0.md](release-v0.4.0.md)。

## 0.3.4 已公开预发布的历史记录

日期：2026-10-11。发布对象：Windows 0.3.4、Android 0.3.4（版本码 5）。这是预发布测试版，没有完整目标平板验收记录。

| 检查 | 结果和边界 |
| --- | --- |
| Windows 核心 | 36 项通过：路径、允许范围、ZIP、认证、校验和恢复等。 |
| Android 核心 | 37 项通过，运行实际核心 Java 代码与生成测试数据。 |
| Windows/Android TLS | 5 项通过，真实 JVM Android 网络客户端与 Windows 回环服务互通；不证明校园 Wi-Fi 可互通。 |
| 库位置与目录状态 | 39 项通过，使用模拟文件提供器，不代替 MatePad SAF 移动/授权。 |
| Android 外观解析 | 21 项通过，覆盖缺失、无效、超限、系统模式和不同颜色。 |
| Android UiTheme 配色 | 28 项通过，运行实际配色代码，覆盖中性背景、文字/滑块对比度和极端强调色；不是原生渲染测试。 |
| Windows 原生外观 | 106 项通过，覆盖按钮黑角、滚动条、菜单/句柄、范围状态和模拟缩放。实际本机 175% DPI，其余比例是模拟；不是实际触控板录像或跨屏验收。 |
| APK 构建与签名 | Java/DEX/资源/对齐成功，v2/v3 签名验证成功；公开源码的无签名构建、对齐和 Manifest 检查也通过，不发布无签名 APK。 |

Android 没有可运行的模拟器或已连接设备，所以没有 APK 原生截图、触摸、相机和实际 SAF 验收。MatePad Air 12 / HarmonyOS 4.2 的快速滑动、横竖屏、微信入口、扫码、持久目录授权及 Obsidian 刷新按 [acceptance.md](acceptance.md)检查。

官方 APK SHA-256：`f493063848e1bed654760cedbcfa1b389a555b70b68c2427700e4eaaff8e096e`。

公开签名证书 SHA-256：`c73736bc5a97a8030b597c004b494613ef221b86bb9a9ec228a71550e3837be7`。这只是证书指纹，不含私钥；旧测试版证书一致，可覆盖更新。

Windows EXE SHA-256：`54209fb166b323db78fdd5b75d89e2fa50bbae9655dbe315447e2b649201ba66`。DPI 配置 SHA-256：`2ea4c99e3c0a2f7fa50d84dd67d47ee165fc3243a0e526b83724d9b29106442a`。

本轮不读取真实笔记或公开本机日志。构建/检查成功不能写成真实硬件、微信或系统文件提供器功能已经通过。

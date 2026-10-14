# VaultBridge 0.4.2 预发布

版本日期：2026-10-14。Windows 与 Android 均为 0.4.2，Android 版本码 8；0.4.1 的手机称呼修正并入此版，不单独发布 0.4.1 Release。此版本仍是预发布，目标平板和系统安装流程待真机验收。

## 下载

- [Windows 便携版](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/VaultBridge-Desktop-0.4.2-portable.zip)
- [Android APK](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/VaultBridge-Android-0.4.2.apk)
- [两端完整包](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/VaultBridge-0.4.2-local.zip)
- [SHA256SUMS.txt](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/SHA256SUMS.txt)：以上三个资产的 SHA-256。

**旧 0.4.0 / Android 0.4.1 没有应用内更新入口，需先手动安装一次本版。** Android 同签名覆盖安装，不先卸载；Windows 完整解压，保留同名 `.exe.config`，从托盘退出旧程序后打开新版。

## 新增功能

- 两端启动后后台检查本项目公开 GitHub Release，展示新版本与简短说明；可手动检查或关闭启动检查，包含本项目预发布版本。
- 用户点击才下载，使用 GitHub SHA-256 digest 校验。缺失有效 digest 时仅提供发布页，不直接下载；失败不显示“已最新”或“已安装”。
- Android 进一步核对 APK 包名、版本和当前安装签名，使用临时只读 URI 打开系统覆盖安装页，来源权限和最终安装由用户确认；授予来源权限并返回后需再次点“安装更新”。
- Windows 安全限量解包便携 ZIP 到新目录，点击切换前说明会停止分享；启动新版后保留旧文件与本地设置，不替换运行中的 EXE。
- Android 的手机/平板静态称呼按完整屏幕短边 dp 区分，避免横屏、分屏窗口误判；不替换用户文件名、笔记正文或设备名。

更新请求不发送笔记、库目录、配对凭据或设备名，不使用 GitHub token。笔记同步仍免费、无云端；互联网不可用时保留原有离线与局域网功能。普通文件互传、笔记手动回传、4 MiB 断点续传和同名保护规则继续保留。

## 验证与使用边界

Windows 与 Android 本机构建通过，APK 0.4.2 / 版本码 8 签名验证通过，证书与旧版一致。Windows 核心/传输/更新 137 项、实际 Java 互传 29 项、TLS 5 项、Android 核心 37 项、目录 39 项、SAF 发布模拟 28 项、更新 60 项、外观解析 21 项、UiTheme 28 项及 Windows 原生 UI 126 项通过。UI 检查包含更新提示、顶部布局及五档 DPI 深浅色边界；本机实际 175% DPI，其他比例为模拟，不代替实际跨显示器或触控验收。分发资产哈希见本版 Release 的 SHA256SUMS.txt，详细状态见 [testing.md](testing.md)，操作说明见 [updates.md](updates.md)。

自动代码与模拟测试不能代替 Android 系统安装、HarmonyOS 来源权限、真实 SAF、微信、扫码、快速滑动或 Windows 运行中切换验收。先用临时库，按 [acceptance.md](acceptance.md)检查。保留旧版本和缓存，不自动清理；源码采用 MIT，作者 Nitro，分发包包含第三方许可证。

发布后补充：Android 实际更新客户端下载与 SHA-256 核验通过。Windows 本次自动执行环境的 HTTPS 凭据层失败，最简 .NET / WinHTTP 对照也未能收到 HTTP 响应；真实 Windows 下载、安装/切换仍待正常桌面环境验收，详情见 [testing.md](testing.md)。本版四个分发资产保持原字节不变，可从上述链接手动安装。

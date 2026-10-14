# VaultBridge

作者：**Nitro**（[@npaper2333](https://github.com/npaper2333)）。

把 Windows 电脑上的 Obsidian 笔记按需送到 Android 手机或平板：同一局域网增量同步，或从微信接收 ZIP 后直接解压到笔记库。电脑是主库，Android 端接收副本。

**免费、无云端、MIT 开源。当前版本为 Android 0.4.3 / Windows 0.4.2 预发布。** Android 新增最近接收/查看、搜索筛选和离线只读预览；应用内更新、普通文件互传、分块续传和手动笔记回传继续保留。

[下载安装包](https://github.com/npaper2333/VaultBridge/releases/tag/v0.4.3) · [快速上手](docs/quick-start.md) · [接收文件与预览](docs/file-library.md) · [应用更新](docs/updates.md) · [源码构建](docs/building.md) · [问题反馈](https://github.com/npaper2333/VaultBridge/issues)

## 下载与兼容性

**适用于 Android 8.0（API 26）及以上的手机和平板；鸿蒙设备需支持安装 Android APK。** 最低版本要求不代表所有机型已经实测；实际安装、目录提供器和系统限制可能因设备而异。

| 平台/文件 | 用途 |
| --- | --- |
| [Windows 便携版 ZIP](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/VaultBridge-Desktop-0.4.2-portable.zip) | 完整解压后运行 EXE；保留旁边同名 `.exe.config`，用于高清缩放。使用 .NET Framework 4.8。 |
| [Android 0.4.3 APK](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/VaultBridge-Android-0.4.3.apk) | 手机/平板共用，目标 API 35、版本码 9；已有同签名版本覆盖安装，不先卸载。 |
| [Android 0.4.3 完整包](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/VaultBridge-Android-0.4.3-local.zip) | APK、Android 指南、文件/预览/更新/验收说明、校验和、MIT 与第三方许可。 |
| [Android 0.4.3 SHA256SUMS.txt](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/SHA256SUMS.txt) | 核对本次 APK 和 Android 完整包；[Windows 0.4.2 校验](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.2/SHA256SUMS.txt)保留在其原 Release。 |

**应用内更新入口从 0.4.2 起提供。旧 0.4.0 / Android 0.4.1 需先手动安装一次对应平台新版，可直接装 Android 0.4.3 / Windows 0.4.2。** 本次不发布 Desktop 0.4.3，Windows 更新器跳过缺少其平台资产的 Release。历史版本保留。

Huawei MatePad Air 12 / HarmonyOS 4.2 是重点验收设备之一，不是专属运行范围；其系统安装、触控、微信入口和目录授权仍需实测，其他机型也按 [验收清单](docs/acceptance.md)检查。

APK 使用项目本地测试签名，Windows EXE 未签署发行者证书。源码仓库不含签名私钥、SDK/JDK、安装包或笔记；安装包放在 Releases。自行构建的 APK 需用自己的密钥签名，通常不能覆盖官方签名版本。Obsidian 与微信需自行安装，本项目不是它们的官方产品。

本地构建产物按 `dist/<版本族>/<完整版本>/` 整理，例如 Android 0.4.3 在 `dist/0.4/0.4.3/`，Windows 0.4.2 在 `dist/0.4/0.4.2/`；同版程序、配置、ZIP、校验与说明放一起。混合总包归较新 Android 版本目录，旧公共文件无法确定归版时保留在 `dist/shared/`。GitHub Release 的下载文件名不变。

## 能做什么

| 功能 | 行为 |
| --- | --- |
| 局域网同步 | 电脑选择允许分享的范围；平板选择整个库、文件夹或具体文件，只拉取新增或变化的内容。文件夹范围包含以后新增笔记。 |
| 微信 ZIP 导入 | “用其他应用打开”选择 VaultBridge，应用解压并保存至所选库；也可在应用内选择 ZIP。支持中文目录和附件。 |
| 备份与冲突 | 更新前备份；平板已有本地编辑时保留原笔记，将电脑版本放进冲突记录。支持预览、采用来源版本与恢复旧版。 |
| 多库与目录整理 | 记住/切换库位置、浏览、新建文件夹、重命名、移动、维护同步目录对应；可以复制笔记到另一空库，保留原库。 |
| 自动外观 | 固定“晴湖书架”布局，读取当前库的基础黑/白模式和强调色；中性背景、适度圆角，少量重要按钮使用强调色。 |
| 离线使用 | 局域网不需要互联网；电脑关闭后，平板已接收的笔记仍可在 Obsidian 离线阅读。 |
| 普通文件互传（0.4.0） | 独立接收目录，选择文件或文件夹，两端手动互传；ZIP 原样保存，同名变化另存，电脑接收逐批确认。 |
| 大文件与续传（0.4.0） | 8 GiB 单文件、4 MiB 分块、暂停/继续、持久任务队列，网络故障有限退避后只补缺失块。 |
| 平板笔记回传（0.4.0） | 平板主动勾选笔记/附件，默认电脑主库根目录，也可填库内子目录；电脑确认，同名变化另存。 |
| 应用更新（0.4.2） | 启动后台检查可关闭，显示新版本说明；点击才下载并校验。Android 交系统确认安装，Windows 点击切换后停止分享，旧文件和设置保留。 |
| 文件与预览（Android 0.4.3） | “文件”页查看最近接收/最近查看、搜索文件名和筛选类型；图片/PDF/文本与 Markdown 源码离线只读预览，其他格式交外部应用打开。 |

不自动回写电脑，不传播删除，不建设云服务。0.4.0 的手动回传与常规主库同步分开，见 [普通文件与笔记回传](docs/file-transfer.md)。外观配置只读，不同步 `.obsidian` 或执行主题 CSS。

## 五步开始

1. 完整解压 Windows 0.4.2 包，打开 `VaultBridge-Desktop-0.4.2.exe`，选择电脑主库和分享范围，点击“开始分享”。防火墙若询问，仅按实际可信网络授权。
2. 把 `VaultBridge-Android-0.4.3.apk` 发送到手机/平板并覆盖安装，按系统提示为当前来源授予安装权限，保持安全检查开启。
3. 平板打开 VaultBridge，选择目标文件夹；在 Obsidian 中使用同一个文件夹。建议首次使用临时测试库。
4. 两端连接同一局域网，平板扫码电脑二维码，或粘贴电脑端复制的配对文字。
5. 平板“选择笔记并同步”；以后用“同步上次范围”。电脑窗口关闭后静默留在托盘，右键“退出”完全停止。

微信 ZIP：打开压缩包 →“用其他应用打开”→ VaultBridge，选择笔记库导入。没有此入口时下载 ZIP，再在应用中“选择 ZIP”；解压和保存由应用完成。也可选择作为普通文件原样传给电脑。

普通文件互传从 Windows“普通文件”、Android“文件传输”进入；手机/平板笔记回传从“电脑同步”进入。电脑接收每批都要确认，目标与同名规则见 [普通文件与笔记回传](docs/file-transfer.md)。Android 的“文件”页用于查看已经接收完成的普通文件，规则见 [接收文件与预览](docs/file-library.md)。

## 数据保护与限制

- 相同内容跳过；更新前保留旧内容；本地修改形成冲突而不静默覆盖。首次同名不同内容也作为冲突处理。备份位于库内 `.vaultbridge/`，不自动清理。
- ZIP 可选择“新建批次”或“合并更新”；电脑端可导出带清单的 ZIP。与联网同步维护同一批笔记时，选择同一目标库、合并模式、子目录留空。
- 目录整理先展示影响，使用系统文件提供器能力；不支持的移动禁用。明确的 Markdown/Wikilink 引用可备份后修复；歧义、插件语法和引用式链接仍需人工检查。
- 使用 TLS、首次配对证书指纹和独立设备授权；不是第三方安全审计结论。访问同网设备的前提是网络允许互通，校园/访客 Wi-Fi 可能隔离设备。
- 隐藏目录、`.obsidian`、符号链接不分享/导入。选择部分笔记时，跨目录图片与附件须一并选择；不自动补齐全部链接依赖。
- ZIP 最大 512 MiB，解压总量 1 GiB，单文件 256 MiB，最多 20,000 条目；不支持加密、分卷或 ZIP64。0.4.0 普通文件单个最多 8 GiB/每批 64 GiB；常规笔记获取与普通互传均支持分块续传，两端都需更新。
- 当前不含云同步、自动发现、路由器/VPN 配置或开机启动；异地地址仅在用户自行具备可达网络时使用。
- 应用检查更新需要访问公开 GitHub，不发送笔记、目录、配对凭据或设备名；断网仍可使用原有本机/局域网功能。详情见 [应用更新](docs/updates.md)。
- 卸载会失去应用私有配对和记录。更新请覆盖安装；操作真实库前先完成临时库验收。

## 开发与验证

原生 Windows Forms / C# 与原生 Android / Java；无需 Node 或 WebView 来运行正式应用。便携依赖只放项目 `tools/`，不修改全局 PATH。公开源码支持无签名 APK 构建，不提供发行私钥。

```powershell
# Windows，项目根目录，PowerShell 7 推荐
./scripts/prepare-tools.ps1 -Download
./scripts/build-desktop.ps1
./scripts/build-android.ps1 -Unsigned
./scripts/test.ps1
```

详细前提、签名方法和排错见 [构建说明](docs/building.md)。自动检查覆盖核心、跨端 TLS、目录状态、外观解析和配色；Windows 外观有原生回归，Android 配色检查不等于平板触控/渲染验收。见 [验证状态](docs/testing.md) 和 [真机清单](docs/acceptance.md)。

## 文档与参与

- [Windows 使用](docs/desktop-quick-start.md)、[Android 使用](docs/android-quick-start.md)、[完整使用说明](docs/quick-start.md)、[常见问题](docs/faq.md)
- [外观规则](docs/appearance.md)、[功能约定](docs/design.md)、[协议](docs/protocol.md)、[第三方许可](docs/third-party.md)
- [更新记录](CHANGELOG.md)、[贡献指南](CONTRIBUTING.md)、[安全与隐私](SECURITY.md)、[后续计划](docs/roadmap.md)

VaultBridge 自身代码与文档采用 [MIT License](LICENSE)，第三方组件保留各自许可。问题反馈请勿附真实笔记、配对文字/二维码、授权令牌或签名私钥。

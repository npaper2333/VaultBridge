# VaultBridge 0.4.0 预发布

发布日期：2026-10-13；本机构建与回归日期：2026-10-12。Windows 与 Android 版本均为 0.4.0，Android 版本码 6。此版本为预发布，目标平板仍待真机验收；[0.3.4 历史预发布](https://github.com/npaper2333/VaultBridge/releases/tag/v0.3.4)保留。

## 下载

- [Windows 便携版](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.0/VaultBridge-Desktop-0.4.0-portable.zip)
- [Android APK](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.0/VaultBridge-Android-0.4.0.apk)
- [两端完整包](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.0/VaultBridge-0.4.0-local.zip)
- [SHA256SUMS.txt](https://github.com/npaper2333/VaultBridge/releases/download/v0.4.0/SHA256SUMS.txt)：包含以上三个资产的 SHA-256。

完整发布页见 [v0.4.0](https://github.com/npaper2333/VaultBridge/releases/tag/v0.4.0)。

## 新增功能

- 普通文件独立入口，不必选择 Obsidian 库。电脑可发送文件或文件夹，平板可选文件或目录传回电脑独立收件箱；ZIP 原样保存，目录结构与文件内容保留。
- 4 MiB 分块，单文件最多 8 GiB，单批最多 64 GiB、20,000 个文件；分块与完整文件均做 SHA-256 校验。
- 持久任务队列、暂停/继续、重启后手动继续。网络故障有限重试，复用已经验证的块，完成文件跳过。
- 平板主动勾选笔记与附件回传电脑当前 Obsidian 主库，默认根目录，可填安全的相对子目录。电脑逐批确认后才写入；相同内容跳过，同名变化编号另存。
- 常规电脑 → 平板笔记获取加入分块续传，仍使用原有备份、冲突与路径对应规则。
- 窗口、托盘、EXE 与 APK 使用 VB 字标；固定晴湖布局、自动 Obsidian 深浅色/强调色、中性背景与圆角要求继续保留。

这次增加的是手动文件操作，电脑仍是笔记主库。不自动合并笔记、补选附件、修复回传链接、覆盖主库或传播删除。普通文件批准和笔记回传批准分别处理，不把已有读取配对变成写入权限。

## 安装与使用

两端一起更新到 0.4.0。Windows 完整解压分发包，运行 `VaultBridge-Desktop-0.4.0.exe`，保留旁边同名 `.exe.config`。Android 使用 `VaultBridge-Android-0.4.0.apk` 同签名覆盖安装，避免卸载导致私有记录和配对丢失。原 0.3.4 构建保留。

电脑“普通文件”选择发送内容和独立收件箱；平板“普通文件”选择接收位置、发送来源和任务。笔记回传从平板“电脑同步”中的“选择平板笔记传回电脑”进入。完整操作和断网处理见 [file-transfer.md](file-transfer.md)，首次使用步骤见 [quick-start.md](quick-start.md)。

## 验证与限制

本机两端编译与自动回归已完成：Windows 核心与传输 74 项、原有 TLS 互通 5 项、Android 核心 37 项、目录状态 39 项、外观解析 21 项、UiTheme 配色 28 项、双向传输 29 项、接收暂存发布 28 项及 Windows UI 107 项通过。双向测试使用生成文件、实际 Java 客户端和 Windows 回环 TLS，含 270 MiB 文件及人为切断真实测试连接后的自动续传；接收暂存发布使用模拟文件提供器。

回归包含接收路径保护、SAF 暂存发布及任务列表滚动修改。APK 已通过 Native Android SDK 构建与 v2/v3 签名验证，最终文件哈希以分发包内的校验和为准。8 GiB/64 GiB 是协议上限，不表示已在目标平板传满上限。自动回归不证明 MatePad SAF、微信、相机、原生显示/快速滑动、后台行为或校园 Wi-Fi 已验收；人工清单见 [acceptance.md](acceptance.md)，历史验证记录见 [testing.md](testing.md)。

接收需要块、完整暂存和最终文件的额外空间，发送暂存也占用应用私有空间，不自动清理。Android 普通接收只写应用新建并记录的 `.partial` 暂存，长度与 SHA-256 校验后通过提供器重命名发布最终原名或编号名；失败保留暂存，不占用最终文件名、不标记成功。当前需要接收目录的文件提供器支持重命名，不支持则明确报错。系统结束应用后不会无限后台运行，重开后手动继续。常规笔记获取与 ZIP 导入保留原有大小限制。

构建与自动测试使用生成文件，不读取真实笔记，不修改系统防火墙、证书、PATH 或 APK 签名密钥。源码与文档采用 MIT，作者 Nitro；发行包附带 MIT 和第三方许可证，源码不包含签名私钥或本机测试日志。

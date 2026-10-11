# VaultBridge v0.3.4 · 首个开源预发布版

Windows 电脑主库 → Android 平板 Obsidian 副本，支持同网按需增量接收与微信 ZIP 导入。免费、无云端、MIT 开源。

作者：**Nitro**（[@npaper2333](https://github.com/npaper2333)）。

## 下载

- **Windows**：`VaultBridge-Desktop-0.3.4-portable.zip`，完整解压，保留 EXE 旁的同名 `.exe.config`。
- **Android**：`VaultBridge-Android-0.3.4.apk`，Android 8.0 / API 26 起。已有同签名测试版直接覆盖安装，不要先卸载。
- **两端完整包**：`VaultBridge-0.3.4-local.zip`，含程序、使用说明、配置、校验和与许可。
- **SHA256SUMS.txt**：核对以上资产的 SHA-256。

## 本版变化

- Windows 页面/任务记录/目录列表改用独立缓冲滚动条，修复快速滚动时白条闪现；保留滚轮、拖动、键盘和文字选择。
- Android 页面、弹窗、输入、列表和扫码页统一中性深浅色、适度圆角、灰色原生滚动条；重要操作使用少量强调色。
- 两端固定晴湖书架布局，跟随当前库 `.obsidian/appearance.json` 的基础模式与强调色。
- 保留 TLS 配对、范围选择、ZIP 接收、多库目录管理、旧版本备份和本地编辑冲突保护。
- 首次公开 MIT 源码，补齐构建、使用、贡献、安全、验证和第三方许可；公开源码可构建无签名 APK。

## 验证与使用边界

Windows 核心 36、Android 核心 37、跨端 TLS 5、目录状态 39、外观解析 21、Android 配色 28 项通过；Windows 原生外观回归 106 项通过。APK Java/DEX/资源/对齐与 v2/v3 签名验证通过。

另已验证公开源码的无签名 APK 构建，不需要发行私钥；Release 只提供已签名可安装 APK。源码允许列表、文档相对链接与脚本语法检查通过，安装包附 MIT 与第三方许可/NOTICE。

仍未完成 Huawei MatePad Air 12 / HarmonyOS 4.2 真机验收：安装、快速滑动、扫码、微信入口、SAF 授权/移动和 Obsidian 刷新须先在临时库检查。Android 配色回归不是原生渲染/触控验收，因此本版标记为预发布。

电脑为主库，平板只接收；不自动回写、传播删除或清理备份。APK 是项目测试签名，Windows EXE 未签署发行者证书。源码和发行包不含私钥、配对材料或真实笔记。

完整说明见 [README](https://github.com/npaper2333/VaultBridge#readme)，问题反馈使用 [Issues](https://github.com/npaper2333/VaultBridge/issues)，先遮盖真实笔记、地址和二维码。

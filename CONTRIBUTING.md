# 参与贡献

欢迎通过 Issue 提交可复现的问题或功能建议，通过 Pull Request 提交小范围变更。安全漏洞按 [SECURITY.md](SECURITY.md) 报告，不公开敏感材料。

## 开发前

1. 阅读 [项目规则](AGENTS.md)、[构建说明](docs/building.md)、[功能约定](docs/design.md) 与 [协议](docs/protocol.md)。
2. 使用自行生成的临时库和测试笔记；不读取、上传真实笔记或已有配对材料。
3. 保持免费、无云端、电脑主库单向接收、备份优先、不自动传播删除的边界。大范围改变先在 Issue 中说明具体问题与方案。

源码在 `desktop/` 与 `android/`，公共测试在 `tests/`，脚本在 `scripts/`，文档在 `docs/`，第三方许可在 `licenses/`。工具、构建和测试输出分别放 `tools/`、`dist/`、`work/`，不提交。文本 UTF-8，新增文件使用英文 ASCII 名。

## 修改与验证

- Windows 保持 .NET Framework 4.8 与系统 C# 编译器兼容；Android 使用 Java 8 语法、最低 API 26，不无意引入更高版本 API。
- 数据写入必须校验成功后更新状态；失败不能记录整批成功。新行为的测试应能发现真实错误，不只照抄实现。
- 使用 `scripts/build-desktop.ps1`、`scripts/build-android.ps1 -Unsigned` 与 `scripts/test.ps1` 验证相关改动。只改 Android 外观时可以使用 `-SkipDesktopUi`，但不能跳过受影响的核心回归。
- UI 修改应说明实际设备、系统和显示比例。Android JVM 配色测试不能当作 APK 原生截图或触摸验收；Windows 模拟缩放不能当作跨显示器验证。
- 不加入密钥、令牌、真实路径、QR 配对信息、SDK/JDK、安装包、生成日志或新的全局依赖。引入第三方组件时补充来源、版本、许可证和必要 NOTICE。

PR 描述说明触发问题、修改后行为、验证方法及限制。贡献按仓库 MIT 许可证提供，第三方组件维持其原许可。

## 问题报告

使用中遇到问题或有建议，欢迎通过 [GitHub Issues](https://github.com/npaper2333/VaultBridge/issues) 反馈，或添加作者微信：**`nitro1447`**（备注“VaultBridge 反馈”）。

请提供应用版本、操作系统/设备、最小复现步骤、预期和实际结果。截图先遮盖库名、正文、连接地址和二维码。涉及 ZIP/文件时，提供自建的小型示例，并说明是否出现本地修改、断网或目录调整；不上传真实库。

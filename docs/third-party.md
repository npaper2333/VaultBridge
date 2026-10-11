# 开源组件与构建来源

- Windows QR：QRCoder 1.6.0，MIT，https://github.com/codebude/QRCoder/tree/v1.6.0 。DLL 嵌入 EXE，无额外安装。
- Android QR：ZXing core 3.5.3，Apache-2.0，https://github.com/zxing/zxing/tree/zxing-3.5.3 。二维码在设备本地生成/识别，不调用外部扫码服务。
- JVM 协议测试：JSON-java 20240303，Public Domain，https://github.com/stleary/JSON-java/tree/20240303 。仅构建验证使用，不打进 APK。
- Microsoft Build of OpenJDK 17、Google Android SDK 35，仅项目内构建工具，不随使用包分发。
- 下载记录：`tools/download-checksums.json`。Google SDK ZIP 使用官方仓库 XML 中的 SHA-1 校验；其他记录是本机下载文件 SHA-256，不宣称全部与独立官方摘要核对。
- VaultBridge 自身源码和说明采用 MIT，作者署名为 **Nitro**（GitHub `npaper2333`）；见根目录 `LICENSE`。不表示对使用者笔记或第三方 Obsidian/微信产品取得任何权利。
- `licenses/` 和分发包保留 QRCoder MIT、ZXing Apache-2.0 与上游 NOTICE，以及仅用于测试的 JSON-java Public Domain 说明；各组件继续适用原许可。ZXing NOTICE 来自对应 3.5.3 标签，未经改写。

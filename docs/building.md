# 从源码构建

正式应用是 Windows Forms / .NET Framework 4.8 和原生 Android Java，不使用 Gradle、Node、WebView ；笔记和文件传输不依赖云服务。当前构建脚本面向 Windows；其他平台没有经过验证。

## 前提与工具

- Windows，安装 .NET Framework 4.8。系统编译器使用 `C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe`，只构建 Android 时不需要它。
- PowerShell 7 推荐；执行项目脚本时应遵守自己的执行策略，不必修改系统 PATH。
- 下载工具需要互联网：Microsoft Build of OpenJDK 17、Google Android 平台 API 35 / Build Tools 35.0.0，QRCoder 1.6.0、ZXing 3.5.3 与测试用 JSON-java 20240303。使用这些工具需遵守其许可。
- SDK、JDK 和依赖下载到项目 `tools/`，不随 Git 仓库或运行安装包分发。来源和许可见 [third-party.md](third-party.md)。

```powershell
git clone https://github.com/npaper2333/VaultBridge.git
Set-Location VaultBridge
./scripts/prepare-tools.ps1 -Download
```

Google SDK 下载摘要按官方仓库 XML 验证；本机下载 SHA-256 记录在 `tools/download-checksums.json`。JDK 下载入口是 Microsoft 的 JDK 17 更新链接，因此不保证不同时间构建字节完全一致；应记录实际工具版本。脚本下载中断时，先检查网络和失败记录，不通过关闭证书验证解决。

## Windows

```powershell
./scripts/build-desktop.ps1
```

输出 `dist/0.5/0.5.2/VaultBridge-Desktop-0.5.2.exe` 与同名 `.exe.config`。图标由源码生成，QRCoder DLL 嵌入 EXE。发布和运行都需保留配置文件，否则高清缩放行为可能不同。

## Android：先构建无签名 APK

```powershell
./scripts/build-android.ps1 -Unsigned
```

输出 `dist/0.5/0.5.2/VaultBridge-Android-0.5.2-unsigned.apk`，Java 8 字节码、最低 API 26、目标 API 35、版本码 12。脚本在带 `VaultBridge-build-` 前缀的 ASCII 临时目录生成 Java/DEX/资源并对齐，不自动清理。当前两端为 0.5.2 预发布。反馈按钮只打开固定公开 GitHub 新 Issue 页面，公开构建不需要建议服务器或 GitHub 写入凭据，不包含私有管理端。

若 Windows 上 JDK 位于中文目录导致 `java.dll` 无法加载，可通过 `-JdkDirectory 'D:\PortableJdk\jdk-17'` 指定已有的 ASCII 路径 JDK，无需修改全局 PATH。`scripts/invoke-java.ps1` 为源码、JAR 和参数提供 UTF-8 调用桥，避免 Windows 启动器将中文路径损坏；不创建签名密钥。回归脚本还可用 `-ReportDirectory` 指定全新的 ASCII 报告目录，以便旧版跨端测试程序启动 Java。

无签名 APK 不能直接安装。发行签名私钥没有公开，默认不带 `-Unsigned` 的模式只供持有既有项目测试密钥的维护者使用，缺少密钥会停止；不会自动创建或替换密钥。

使用自己已经准备好的签名材料和 SDK 的 apksigner，密码让工具交互式询问。例如：

```powershell
$buildTools = (Get-ChildItem './tools/android-sdk/build-tools' -Directory | Select-Object -First 1).FullName
$jdk = (Get-ChildItem './tools' -Directory | Where-Object Name -Like 'jdk-17*' | Select-Object -First 1).FullName
$java = Join-Path $jdk 'bin/java.exe'
$signer = Join-Path $buildTools 'lib/apksigner.jar'
& $java -jar $signer sign --ks './tools/your-keystore.jks' --out './dist/0.5/0.5.2/VaultBridge-custom.apk' './dist/0.5/0.5.2/VaultBridge-Android-0.5.2-unsigned.apk'
& $java -jar $signer verify --verbose './dist/0.5/0.5.2/VaultBridge-custom.apk'
```

不要公开签名材料。自签版本通常不能覆盖 GitHub Release 的 APK；自行管理版本和数据，不要为了安装先删掉唯一记录。应用真机能力按 [acceptance.md](acceptance.md) 验收。

## 回归

```powershell
# 先构建 Windows EXE，测试需要它与现有 JDK/JAR
./scripts/test.ps1
# 仅 Android 外观修改且 Windows 未改动时
./scripts/test.ps1 -SkipDesktopUi
```

测试使用生成的临时库、回环 TLS 服务及报告，不需要真实笔记库。报告默认放在 `work/validation-*`；项目位于中文路径时会提示使用 ASCII 临时报告根，也可用 `-ReportDirectory` 明确指定新的 ASCII 目录。Windows 原生回归需要本机可用的桌面会话；回环监听和相机/文件提供器真机检查不是同一种测试。验证边界见 [testing.md](testing.md)。

测试读取 `dist/0.5/0.5.2/` 的新 Windows 程序；原生 UI 分支把同字节 EXE/配置复制到本轮报告的 `desktop-ui-host/bin/`，并放置 `desktop-ui-host/tests/appearance-cases.json`。不在分发目录放测试数据，不修改旧版本程序字节。

更新回归使用合成 Release/资产、ZIP 和下载内容。JVM 只编译 `UpdateInfo.java`、`UpdateClient.java` 与 `tests/UpdateTest.java` 等纯 Java 依赖，原生更新 UI/Provider 由 Android SDK 构建，不交给桌面 JVM。预览和自测不自动访问 GitHub、不启动真实安装器。

0.4.3 的 `RecentFiles.java` / `PreviewRules.java` 和对应两份 Test 也进入纯 Java 回归；它们使用既有 Engine/TransferEngine、JSON-java 和 JDK 依赖。原生文件列表与预览 Activity 只由 Android SDK 编译，JVM 规则通过不代表真实 SAF、图片/PDF 或手势已验收。

若运行环境的沙箱阻止原生工具或隐藏测试窗口，使用该环境正常授权机制；不要修改系统安全设置、代理或权限来绕过。`aapt2` 在中文路径或受限运行环境可能异常，脚本已经将中间文件置于 ASCII 临时路径。

## 打包

维护者在两端构建和验证完成、有**已签名** APK 后运行 `scripts/package.ps1`，生成双端总包、Android 包和 Windows 便携包。`-AndroidOnly` 仅打 Android 包。包内包含操作指南、设置/文件传输/文件库/验收/更新说明、校验和、MIT 与第三方许可；不包含私有作者端、服务管理模块、私钥、SDK/JDK 或笔记。无签名 APK 不能当作可安装发行包。

分发目录按版本族/完整版本排列：本版两端使用 `dist/0.5/0.5.2/`；旧版本目录完整保留。暂存位于新的 `work/packaging-<platform-versions>-<timestamp>/`；新 ZIP、独立指南和校验文件带时间戳，不重写旧版公共文件。无法可靠归版的旧公共文档在 `dist/shared/`，不猜测归版或清理。

0.5.2 以预发布形式分发。公开源码从新的允许列表副本导出，不包含本地规则、日志、私有作者端或服务端；新增发布说明也需进入允许列表。公开前验证源码与包，远端核验后再确认发布成功；不以本机构建充当已上线证据。历史版本的下载继续保留；更新器跳过不含对应平台资产的 Release。

版本号目前在构建脚本、Android Manifest、两端 UI 与说明中显式维护。提交版本升级时同时检查这些位置，并写入 CHANGELOG；不改变既有应用包名或签名来强迫卸载。

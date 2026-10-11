# VaultBridge 协议 v1

Windows 提供 TLS 1.2 TCP 服务（默认端口 27183）。客户端必须匹配首次扫码获得的服务器证书 SHA-256 指纹，指纹变化必须重新配对，不接受任意自签名证书。

每条连接一个请求。消息采用 4 字节大端无符号长度（实际限制为 1 至 8 MiB）和 UTF-8 JSON。下载响应的 JSON 后紧接 `size` 字节文件内容。限制每文件 256 MiB、每清单 20,000 文件。

配对文本 `vaultbridge:` + Base64(UTF-8 JSON)，字段 `v, host, port, pin, code, name`。二维码仅本机生成，不调用外部服务。配对有效 5 分钟，成功使用一次即失效，每 IP 最多 8 次失败；刷新二维码重置窗口。

- `pair` 请求：`action, code, device`。响应：`ok, token, vaultId, name`。
- `manifest` 请求：`action, token, scopes`。scope 是库内相对目录或文件，空字符串表示所有已授权内容。响应：`ok, manifest`。
- `get` 请求：`action, token, path, sha256`。只有文件仍匹配清单 SHA-256 才发送。响应：`ok, size, sha256`，随后原始内容。
- 错误响应：`ok=false, error`；不得附带配对凭据、完整堆栈或库外路径。

清单字段 `vaultId, name, generated, warnings, files`。文件字段 `path, size, sha256, modified`。内容身份依赖 SHA-256；mtime 仅展示。路径采用 `/`，拒绝绝对路径、路径穿越、隐藏目录、Windows 设备名和重解析点。

电脑 ZIP 导出包含 `.vaultbridge-manifest.json`，不导出设备授权、证书或历史备份。APK 必须独立验证 ZIP 的实际内容与清单，不能仅信任清单。

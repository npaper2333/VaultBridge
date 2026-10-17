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

## 0.4.0 分块扩展

保留 v1 配对、帧和指纹校验，新增 `x-*` 操作。每连接一请求，所有扩展请求必须带设备 `token`，不把它写入普通任务文件。块为 4 MiB，普通文件最大 8 GiB/每批 64 GiB，20,000 文件；笔记清单仍按原限额。

- `x-list`：电脑选定的普通文件清单，响应含随机 `id`、`files`、`warnings`、`chunk`、`maxFile`。
- `x-get`：`id,path,sha256,block`；校验清单授权与来源快照，响应 `size,sha256` 后接一个块。`x-note-get` 使用已获取的当前笔记清单，并重新检查原笔记分享范围。
- `x-request`：`id,mode(files|notes),prefix,files`。任务 ID 为 32 位小写十六进制。服务端持久创建 `pending` 状态，电脑本地确认根目录后变为 `approved`，拒绝为 `rejected`。同 ID 重放必须内容、方向和前缀一致。任务绑定设备令牌的 SHA-256，不接受其他设备续写；配对读取授权不能自动变成写入授权。
- `x-status`：`id,file`；响应审批状态、当前文件是否完成/实际保存相对路径、已校验块 `{index,sha256}`。
- `x-put`：`id,file,block,sha256`；先响应 `ready`，客户端再发送该块精确字节数，服务端持久保存且验证后确认。未知确认结果时用 `x-has` 查询相同字段，不凭文件长度认定成功。
- `x-commit`：`id,file`；合并块、验证完整长度/哈希，按相同跳过或编号另存规则保存，复核目标后持久记录完成。重复提交返回同一保存路径。电脑可本地撤销后续写入，已保存文件保留。

所有路径为根目录内相对路径，拒绝隐藏配置、穿越和链接，包括接收根目录的链接祖先。批准与发布前都检查实际目标：普通模式即使选择主库的上级收件箱，也不能沿子路径进入当前主库；`notes` 回传只可写入电脑确认的当前主库。任务不得由远端指定任意电脑绝对根目录。客户端只对网络错误有限退避重试；验证/授权/路径/空间错误保持明确失败，不自动重试。

## 只读对比扩展（0.5.2，沿用本地 0.5.0）

沿用 TLS 指纹与设备授权，兼容旧 `x-*` 操作。对比不创建接收任务、不赋予写权限。

- `x-compare`：`token,mode=notes,prefix`，返回 `ok,vaultId,targetId,mode,prefix,scopes,complete,warnings,files,checkedAt`。仅遍历当前电脑分享范围。客户端验证每条路径属于声明范围；只有完整根范围且无警告时才能从清单缺项推断当前可分享库中缺少。隐藏配置等始终排除。
- `x-commit` / 已完成的 `x-status` 在原字段外增加 `targetId,vaultId,sha256,size,completedAt`；记录与实际保存路径一起持久保存。旧任务未记录的字段不能补作成功证据。
- `x-verify`：`token,id,file,targetId`，仅检查本设备原批准任务的已完成文件，重新验证当前根目录/库身份/批准状态/实际文件大小与哈希。返回 `result=same|changed|missing|unknown` 及 `targetId,vaultId,mode,prefix,saved,sha256,size,completedAt,checkedAt`。拒权、撤销和目标变化不能解释成缺少。

`targetId` 是绑定模式、电脑根目录、子目录和库身份的非明文标识。客户端将它与配对身份及来源哈希一起使用；历史回执本身不是当前存在证明。仅同一目标计划路径、大小与 SHA-256 的本次核验可支持隐藏。另存到编号路径或其他位置只标记，不能隐藏原计划路径。客户端对离线、旧端点或不完整回复保持未知，普通发送仍可使用旧流程。

0.5.2 公众反馈只在外部浏览器打开固定 GitHub 新 Issue 页面，不属于本协议，不传递局域网设备令牌或旧草稿。需要登录 GitHub 且提交内容公开，由用户在网页最终提交；不包含 App 内建议 POST 或私有管理接口。说明见 [设置与建议交流](settings-feedback.md)。

# Tailscale 直连操作指南（AgentBridge 0.3.7）

适用：被控手机有可用 Root 或 Shizuku，Agent 在另一台电脑或手机上运行。无需公网服务器、端口映射或 `tailscale serve`。

## 1. 两台设备接入同一网络

- 双方安装、登录官方 [Tailscale](https://tailscale.com/download)。Android 官方说明：https://tailscale.com/kb/1079/install-android 。
- 被控手机打开 Tailscale 的 VPN 连接开关，控制端也保持 Tailscale 在线。
- 确认属于同一 tailnet，或设备已按规则共享；管理后台的 grants / ACL 必须允许控制端到手机的 TCP8443。不要只用能否ping来判断8443是否可达。
- Android 同时通常只能保持一个 VPN；其他 VPN 可能把 Tailscale 挤下线。按你的网络需要配置，不要假设两个独立 VPN 总能同时运行。
- 在 Tailscale 中找到被控手机的100.x地址，例如 `100.88.12.34`。也可以用已启用 MagicDNS 的名称，但首次建议用IP减少DNS问题。

## 2. 授权手机执行后端

AgentBridge → 权限：会直接显示上次选择的Root或Shizuku配置；首次可选择后端，再点击对应授权按钮。

KSU / ReKSU：在管理器允许 AgentBridge 超级用户权限，然后点「申请/重试 Root 连接」。等待 **Root 已就绪 · UID=0 · 执行自检通过**。如果只显示“授权 UID=0，但执行服务未就绪”，说明授权已经成功，应排查新提示的本地服务错误，不要把它当成未root。

若读取不到UID，新版会明确说明读取失败；不会将-1冒充实际系统UID。不要关闭SELinux或盲目修改系统分区。Shizuku需要先通过官方流程启动并授权，通常运行UID2000。

## 3. 保存连接设置

进入连接页：

| 项目 | 示例 |
|---|---|
| 监听 IP | 手机的 `100.88.12.34`，或点自动选择候选 IP（仅IPv4） |
| 监听端口 | `8443` |
| 可信网络 HTTP | 关闭，保留 HTTPS |
| 高级设置 | 可留空；自动包含监听IP和检测到的TailscaleIP，MagicDNS可额外补充 |

点「保存连接设置」。证书后台生成，界面应可继续滚动、切换页面；编辑输入框不会反复解密证书或生成密钥。

首页启动服务。如果失败提示该IP无法绑定，不代表Tailscale账号一定有问题：Android VPN实现可能不允许应用绑定该地址。此时：

1. 停止服务。
2. 连接页点「监听所有接口」，阅读并确认局域网开放提示。监听IP变为 `0.0.0.0`。
3. 保存后重新启动；在控制端把目标URL设置为 `https://100.88.12.34:8443`，被控端无需额外填写它。
4. 首页会明确显示实际监听 `https://0.0.0.0:8443`，控制端实际访问 `https://100.88.12.34:8443`。不要在控制端把0.0.0.0当远程目标。

如果监听IP是 `127.0.0.1`，仅手机自己能访问，其他Tailscale设备不能连接。不要使用这个地址作为最终远程监听配置。

如果Android没有把Tailscale地址显示为本机接口IP，通配监听时可在“高级设置”的可选附加IP/域名中填入控制端将使用的手机TailscaleIP。它只用于证书校验，不是必填连接目标。

## 4. 证书和令牌

- 连接页在保存完成后点「复制 PEM 证书」，经可信渠道传到控制端，原样存为 `bridge.pem`。保留BEGIN/END及换行，不要附加说明文字。
- 对照手机显示的证书SHA256。更改监听IP、检测到的新TailscaleIP或补充证书DNS并重新签发后，需要更新控制端证书。
- 安全页点「批准并复制控制令牌」，只交给可信Agent。Tailscale账号和IP不能替代应用令牌。
- 首页复制设备ID。
- 服务启动后可点连接页「检查本机 API 连接」。这只验证手机本机API/TLS/鉴权，不证明另一设备的ACL已允许访问。

## 5. 控制端验证

在源码根目录安装Python SDK后：

```bash
pip install -c requirements.lock .
export AB_URL='https://100.88.12.34:8443'
export AB_DEVICE='手机首页的设备ID'
export AB_CA="$PWD/bridge.pem"
read -rs AB_TOKEN; export AB_TOKEN
unset AB_RELAY AB_ALLOW_HTTP
agentbridge status
agentbridge screenshot --output screen.png
```

Windows PowerShell可以使用：

```powershell
$env:AB_URL = 'https://100.88.12.34:8443'
$env:AB_DEVICE = '手机首页的设备ID'
$env:AB_CA = (Resolve-Path .\bridge.pem).Path
$env:AB_TOKEN = Read-Host '粘贴控制令牌'
Remove-Item Env:AB_RELAY -ErrorAction SilentlyContinue
agentbridge status
agentbridge screenshot --output screen.png
```

不要复制令牌到公开聊天或Git仓库。CLI点击要求截图返回的imageId；跨CLI调用要指定同一个新会话：

```bash
export AB_SESSION='control-20261101-1'
agentbridge screenshot --output screen.png
# 查看screen.png，把坐标改成你要点击的位置，并填写刚返回的imageId：
agentbridge tap 300 500 --image '刚才的imageId'
agentbridge stop
```

截图超过30秒、屏幕旋转或尺寸变化会拒绝旧imageId。停止后该session不再可用，下一次控制请换一个AB_SESSION。Python SDK会自动携带同一客户端最近截图的imageId，见README。

## 6. Agent / MCP

本地Agent直接调用Python SDK即可。支持MCP的桌面Agent可在同一Tailscale设备上运行 `agentbridge-mcp`，通过stdio接入，传入上述AB_URL、AB_DEVICE、AB_TOKEN、AB_CA。

```json
{"mcpServers":{"agentbridge":{"command":"agentbridge-mcp","env":{"AB_URL":"https://100.88.12.34:8443","AB_DEVICE":"设备ID","AB_TOKEN":"控制令牌","AB_CA":"/absolute/path/bridge.pem"}}}}
```

运行Agent的机器本身必须能够访问tailnet。普通ChatGPT网页或未入网的云端Agent，不会因你粘贴了100.x地址就自动加入Tailscale。本版不再提供手机主动连接公网中转的选项。

## 7. 故障排查

| 现象 | 检查 |
|---|---|
| 连接超时 | 两端Tailscale在线、同tailnet、ACL允许TCP8443；手机前台服务仍运行；实际监听不是127.0.0.1 |
| 无法绑定地址 | 先停服务，明确选择所有接口并保存重启；访问仍用手机100.x地址 |
| 连接被拒绝 | 端口是否8443、服务是否成功启动；手机日志是否有监听失败 |
| TLS证书不受信任 | AB_CA路径正确且是当前手机导出的PEM；不要关闭证书校验 |
| 主机名不匹配 | 控制端使用的IP/域名必须出现在证书SAN；新IP会自动加入，MagicDNS在高级设置中补充，保存后重新复制证书 |
| UNAUTHORIZED | 复制当前令牌；检查空格、旧令牌或令牌已轮换 |
| BACKEND_UNAVAILABLE | 连接页只负责网络；权限页还必须连接Root/Shizuku |
| Root检测失败 | KSU/ReKSU允许本应用；重试清理旧普通shell；复制新版的具体授权/服务错误 |
| STATE/SESSION_BUSY | 停止旧会话，给当前控制设置唯一sessionId；不要同时多Agent操控 |
| 截图黑屏/输入被拒绝 | 安全锁屏、FLAG_SECURE、DRM、安全键盘等可能限制，不承诺绕过 |

后台省电、Doze或厂商限制可能导致服务退出/延迟；按需在系统允许Tailscale和AgentBridge后台运行。应用不保证“永不被杀”。

## VPN 地址参考的来源（0.3.2）

连接页仅查询Android的VPN网络（NetworkCapabilities.TRANSPORT_VPN），读取其LinkProperties中的地址；地址不可用时，只读取同一个VPN的interfaceName对应接口。不会再枚举并显示Wi-Fi/移动网络IP。

Tailscale候选范围为100.64.0.0/10及fd7a:115c:a1e0::/48；必须首先来自系统识别的VPN。其他VPN也可使用相同地址段，故只能称候选，仍以官方Tailscale App为准。没有VPN或系统不公开地址时，列表保持空，可手动填写官方App显示的IP。

连接页顺序：IP/端口同行 → 自动选择候选IP/监听所有接口 → 仅Tailscale候选IP → 保存连接设置 → 证书。无IPv4候选时自动选择不会修改监听值；请手动填写官方App中的IPv4。SHA256为缩略显示，核对时使用“复制完整指纹”。

设备ID和复制按钮现在位于安全页顶部。HTTP开关在“复制PEM证书”下方的高级设置中，默认收起；HTTPS仍为默认。

设置页可切换miuix和Material 3主题；运行日志与关于入口也在设置页。

复制PEM证书下方可直接查看保存文件和--ca使用提示，无需展开高级设置。进入权限页只恢复配置显示，不会自动发起授权。

权限页两个后端的操作按钮分别为“申请/重试 Root 连接”和“申请/重试 Shizuku 连接”。连接页两项IP选择操作使用相同字号；大字体或窄窗口下可上下排列以完整显示文字。

miuix模式下，保存连接后应出现操作结果弹窗，关闭后可再次保存；关于、失败信息及监听所有接口确认也通过同一显示层呈现。权限配置中的当前执行后端只在本地IPC连接和UID有效时显示；未授权时显示当前所选Root或Shizuku名称。

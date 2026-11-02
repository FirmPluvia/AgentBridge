# 旧公网中转部署资料（0.1.x，当前停用）

0.2.0 APK不再提供公网中转入口，也不主动连接此服务。请按 [TAILSCALE.md](TAILSCALE.md) 使用直连。本文件仅保留旧服务的独立部署说明，不代表当前APK支持该连接。

# 公网中转与远程 MCP 部署

## 可信自托管中转

准备域名 DNS 指向服务器、开放 TCP80/443，安装 Docker Engine + Compose v2。在仓库根目录：

```bash
printf 'RELAY_DOMAIN=relay.example.com\n' > .env
docker compose up -d --build
docker compose ps
```

Caddy 自动申请公开证书，HTTPS / WSS 在 Caddy 终止，内部 Compose 网络转 HTTP。只有 Caddy 暴露80/443，relay 8080不发布到宿主机；`AB_TRUST_PROXY=1` 只允许在该隔离拓扑使用。若单独部署 relay，关闭该选项并由 uvicorn 配置 `--ssl-keyfile` / `--ssl-certfile`，或确保只能由可信代理访问。不能在公网开放允许伪造 X-Forwarded-Proto 的 relay端口。

中转能看到并在内存路由操作、文本、截图，属于受信任模型。WSS 只是传输加密，不是 E2EE。协议的 `type/path/body/routeId` 可在未来扩展加密载荷，当前没有宣称已经实现 E2EE。

手机配置 `https://relay.example.com` → 公网中转 → 首页启动。首次注册每设备随机凭证，保存在 Keystore 加密 vault 中；SQLite 保存高熵凭证 SHA256，不保存明文。注册不允许覆盖已有设备。

```bash
pip install -c requirements.lock .
agentbridge --url https://relay.example.com --device '手机ID' pair --name '我的AI工具'
```

控制端显示6位校验码，被控手机显示相同码与控制端名称。**必须在手机本地确认**，否则无 controllerToken。配对3分钟过期，轮询凭证为独立256位随机值，6位码本身不授权访问。控制端输出 controllerToken 后妥善保存，避免终端录屏泄露。

```bash
export AB_URL=https://relay.example.com AB_DEVICE='手机ID' AB_RELAY=1
read -rs AB_TOKEN; export AB_TOKEN
export AB_SESSION='session-1'
agentbridge devices
agentbridge screenshot --output screen.png
agentbridge tap 300 500 --image '返回的imageId'
agentbridge stop
```

手机仅使用移动网络即可，不依赖电脑常驻。断线指数退避1–64秒加随机抖动；服务端无离线操作队列，手机重连不重发旧操作。每个设备最多一个在线执行连接，凭证不能跨设备路由。心跳及 WebSocket ping/pong 用于清理失效连接。服务重启会使未完成配对失效；持久凭证仍在卷内。中转失效时 pending 请求返回 unknown，而不是假称未点击。

手机安全页支持按控制端分配权限/撤销、全撤销、设备凭证轮换、解绑并停止。手机本地的授权名单再次校验每个中转请求，因此代理数据库单独新增控制端无法绕过手机本地首次批准。服务器是可信方，仍能够冒用已经批准的控制端，这不是 E2EE/零信任模型。

备份 `relay-data` 会包含凭证哈希及设备/控制端关系，不包含常规持久化截图。限制卷权限。删除部署时先撤销手机控制端，`docker compose down` 停服务；`docker compose down --volumes` 会不可恢复删除中转与 Caddy 证书数据，应自行确认后使用。

## 远程 Streamable HTTP MCP

适用于支持远程 HTTP MCP 且能发送 Bearer Header 的手机/电脑 agent。不需要另做聊天 APK。MCP 网关使用一个已配对的控制端身份和固定会话；不同信任主体应运行独立实例/凭证。

安装 Python 包后，在与中转相同的服务器或可信网络中运行：

```bash
export AB_URL=https://relay.example.com AB_DEVICE='手机ID' AB_RELAY=1
read -rs AB_TOKEN; export AB_TOKEN
export AB_SESSION='remote-mcp-session'
export AB_MCP_HOSTS='mcp.example.com'
export AB_MCP_ORIGINS='https://mcp.example.com'
export AB_MCP_TOKEN=$(python -c 'import secrets;print(secrets.token_urlsafe(32))')
# 把独立 AB_MCP_TOKEN 通过可信渠道配置给 agent
agentbridge-mcp --http --host 127.0.0.1 --port 8765
```

用 systemd / 容器进程管理器保存受限环境配置并重启服务。AB_TOKEN、AB_MCP_TOKEN 不写进 Git 或 Caddy 配置。Caddy 增加独立域名站点：

```caddyfile
mcp.example.com {
    request_body {
        max_size 64KB
    }
    reverse_proxy 127.0.0.1:8765
}
```

该示例 Caddy 与 MCP 在同一宿主机。如果 Caddy 运行在本文默认容器内，`127.0.0.1` 指 Caddy 容器自身，需要将 MCP 放在同一 Compose 网络并改为 `mcp:8765`，参见下面的可选部署文件。

```bash
# 使用附带覆盖文件，先为 MCP 环境变量设置对应真实值：
export MCP_DOMAIN=mcp.example.com
export AB_MCP_HOSTS=mcp.example.com
export AB_MCP_ORIGINS=https://mcp.example.com
# AB_URL/AB_DEVICE/AB_TOKEN/AB_MCP_TOKEN 也必须存在
# 覆盖 Caddy 配置，同时启动 mcp 容器；不公开8765：
docker compose -f docker-compose.yml -f docker-compose.mcp.yml up -d --build
```

客户端连接 `https://mcp.example.com/mcp`，请求头 `Authorization: Bearer <AB_MCP_TOKEN>`。保留 Host/Origin 校验；环境变量显式列出部署域名，DNS rebinding 防护不关闭。此处 Bearer 是静态受控凭证，不是完整 OAuth 授权服务；只支持 OAuth 登录且不能配置 Bearer 的客户端需另行接入 OAuth 网关，本版不假装支持。

## 后台运行

用户主动开启 specialUse 前台服务，持续通知和停止按钮。`START_NOT_STICKY`，不假装系统杀进程后永久在线。进程被杀重建后需重新启动；开机自动启动默认关闭，用户可开启，但 ROM/系统限制可能阻止。Doze、厂商省电、Tailscale VPN 断开都会使连接中断或延迟，应在系统按需允许后台运行。服务监听网络变化并重连；不会自行修改省电白名单或安全设置。

本版没有 root 开机守护脚本，没有修改系统分区、SELinux 或隐藏应用图标。卸载前停止控制并撤销凭证；辅助输入法可在系统设置禁用，恢复原输入法。

# 精简连接二维码（AgentBridge 0.5）

## 手机操作

连接页保存设置后，点击生成二维码并确认风险。默认不生成、不展示；离页、后台、配置变更或撤销令牌后隐藏。二维码只在内存生成，不自动保存文件或写日志。

0.4按用户设置允许截屏，移除了二维码窗口的FLAG_SECURE限制。二维码与截图都含可控制手机的令牌，泄露后需要在安全页撤销并轮换；它不是一次性验证码，不会自动到期。

展示页可滚动查看并复制“Agent连接提示词”。提示词仅说明如何读取二维码/JSON、安装SDK、连接和操作，不重复嵌入实际令牌；需要同时提供二维码或扫码JSON。

## 五项字段

二维码格式升级为version=2，仅保留：

| 字段 | 含义 |
|---|---|
| version | 二维码配置格式版本，固定2；与App版本0.4、HTTP API版本v1不同 |
| deviceId | 手机设备ID |
| urls | 一个或多个连接基地址，包含协议、主机和端口 |
| token | 当前高熵控制令牌 |
| certificatePem | HTTPS完整PEM公钥证书；HTTP模式为null |

不再导出WS地址、监听IP/端口、指纹、主机名校验开关、API路径、后端/权限状态、App版本或生成时间。WS地址和指纹可推导；后端权限通过连接后的status/capabilities查询。证书私钥和root管理器凭证从不导出。

控制端只选一个可达地址。不会自动重试副作用请求。直接监听局域网/热点真实IP时可直接生成；通配监听需有Tailscale候选或在证书高级设置补充真实IP/域名并保存。回环或通配地址不能作为扫码目标。

使用ZXing core 3.5.4、UTF-8、M级纠错和4模块静区。单个二维码容量不足时明确报错，不截断令牌或证书。

## 控制端

将SDK/CLI/MCP更新到0.5。新客户端支持旧版version=1二维码；旧0.3.8客户端不认识新version=2，需要更新并重启MCP进程。

使用可信扫码工具读取JSON，保存为受保护的connection.json，不要上传到Git或公开聊天记录。在交付源码目录安装Python包后：

```bash
pip install -c requirements.lock .
chmod 600 connection.json
agentbridge --connection-config connection.json status
agentbridge --connection-config connection.json capabilities
agentbridge --connection-config connection.json screenshot --output screen.png
```

多个候选地址时可显式选择其中一个：

```bash
agentbridge --connection-config connection.json --url https://192.168.1.25:8443 status
```

客户端始终启用TLS证书与主机名校验。version=2不需要校验开关或独立指纹，仍使用完整证书创建受信任上下文。手机HTTP模式导出的配置，控制端仍必须显式加--allow-http，不会根据二维码自动放宽安全性。

Python异步SDK：

```python
from agentbridge import AgentBridge
from agentbridge.connection import read_connection

async def control():
    async with AgentBridge.from_connection_config(read_connection("connection.json")) as phone:
        print(await phone.status())
        print(await phone.capabilities())
        shot = await phone.screenshot()
```

本地stdio或按部署文档配置了鉴权的远程HTTP MCP可使用：

```bash
export AB_CONNECTION_CONFIG="$PWD/connection.json"
unset AB_URL AB_TOKEN AB_DEVICE AB_RELAY AB_CA
agentbridge-mcp
```

## 首页自检

自检保留0.3.8的只读检查：连接配置/证书、真实UID、实际PNG截图、input命令、辅助IME、本机API与鉴权。不会自动授权、发送点击/文本或开启监听。输入命令存在不冒称触控实测通过，本机网络成功不代表控制端一定可达。截图只在内存检查，不替换agent最近截图的imageId。

## 验证界限

JVM测试以真实生成的RSA证书编码二维码，再用ZXing解码，验证恰好五个字段且证书/令牌不丢失。Python测试覆盖两代配置、严格TLS及HTTP显式允许。尚未真机相机扫码、截屏、提示词复制或帮助页排版验收。局域网、两种热点和Tailscale的逐步说明见App设置的连接帮助或HELP.md。

0.5精简了展示页提示词，两种主题分别优化；提示词完整初始化背景见AGENT_START.md，UI对应关系见UI_DESIGN_0.5.md。二维码五项字段与允许截屏行为不变。

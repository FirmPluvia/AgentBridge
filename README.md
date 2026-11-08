# AgentBridge · Tailscale 直连版

让 Agent 直接操控你的 Android 手机：真实截图、点击、滑动、输入，全部走你自己的 Tailscale / 局域网，不经过任何公网服务器。

被控端通过 **Root 或 Shizuku** 在手机本地执行操作；Agent 通过 HTTPS API、Python SDK / CLI 或本地 stdio MCP 接入。当前版本已移除 ADB 模式及相关原生库，也没有公网中转入口或主动 WSS 中转连接；`relay/` 与 SDK 的旧中转协议代码暂作独立归档，无需部署服务器。

## 功能

- **真机操控**：截图、屏幕点击、滑动、按键、中文输入、UI hierarchy 查询；可选 shell（默认关闭，需本地明确开启）。
- **直连，不走公网**：Tailscale / 局域网 HTTPS 直连；HTTPS 默认开启，证书须与访问 IP / DNS 匹配，SDK 用 `--ca` 校验，不关闭主机名验证。HTTPS 证书自动包含监听 IP 和检测到的 Tailscale IP，可选 MagicDNS / 其他 IP 在高级设置补充。
- **三种接入方式**：HTTPS API、Python SDK / CLI、本地 stdio MCP；同一份连接配置通用。
- **扫码连接**：连接页生成完整配置二维码（地址、设备 ID、令牌、完整 TLS 证书与指纹、权限），默认隐藏，风险确认后才生成；离页、后台、配置变更或凭证撤销后自动隐藏，不写文件或日志。Python SDK、CLI、MCP 支持扫码 JSON 导入。
- **一键自检**：逐项检查配置/证书、后端实际 UID、真实截图、输入命令、辅助输入法及本机 API/鉴权；只报告结果，不自动点击、输入或申请授权。
- **辅助输入法**：英文/数字/符号按键、大小写、Unicode 退格、编辑器动作/换行、切换系统输入法与收起，保留远程 Unicode 输入。
- **双主题**：Miuix / Material 3 一键切换，立即生效，深浅色跟随系统。
- **安全**：所有控制请求需应用令牌；shell 默认关闭；中文输入需用户启用辅助输入法；坐标取自最近 30 秒内截图的实际像素；停止不能撤销已经发生的点击。


## 快速上手：Tailscale 直连

完整分步说明：[docs/TAILSCALE.md](docs/TAILSCALE.md)。

1. 被控手机、运行 Agent 的电脑或手机都打开官方 Tailscale，登录同一 tailnet，确认两设备在线且 ACL 允许 TCP 8443。
2. 在 Tailscale 中复制被控手机地址，例如 `100.88.12.34`。
3. AgentBridge → 连接：监听 IP 填 `100.88.12.34`，端口 8443，保留 HTTPS，点击「保存连接设置」。也可点「自动选择候选 IP」。没有“访问地址”输入项。
4. 如果监听失败提示地址无法绑定，在连接页明确选择「监听所有接口」并确认，保存后重启服务；仍然用 `https://100.88.12.34:8443` 访问。所有接口可能同时向局域网开放，必须保留鉴权。
5. 连接页复制 PEM 证书，通过可信渠道保存为电脑 `bridge.pem`；安全页批准并复制控制令牌；首页启动服务，可先点「检查本机 API 连接」。

```bash
python -m venv .venv
source .venv/bin/activate
pip install -c requirements.lock .
export AB_URL='https://100.88.12.34:8443'
export AB_DEVICE='首页的设备ID'
export AB_CA="$PWD/bridge.pem"
read -rs AB_TOKEN; export AB_TOKEN
unset AB_RELAY AB_ALLOW_HTTP
agentbridge status
agentbridge screenshot --output screen.png
```

同一 Python 会话内读取截图后点击：

```python
import asyncio
from agentbridge import AgentBridge

async def main():
    async with AgentBridge.from_env() as phone:
        shot = await phone.screenshot('screen.png')
        print(shot['width'], shot['height'], shot['imageId'])
        # 先查看截图，再把300、500改成目标位置的实际像素坐标。
        await phone.tap(300, 500)
        await phone.stop()

asyncio.run(main())
```

MCP：在已接入该 tailnet 的电脑上运行 `agentbridge-mcp`（stdio），配置同样环境变量。云端 Agent 若没有加入你的 tailnet，不能直接访问 100.x 地址；ChatGPT 网页本身不自动成为你的 Tailscale 设备。

## Root / Shizuku 配置

1. 打开 KSU / ReKSU 管理器，在超级用户列表中允许 **AgentBridge**。如使用自定义应用配置，先使用管理器的标准 root 配置。不要关闭 SELinux。
2. AgentBridge → 权限 → 选择 Root →「申请/重试 Root 连接」。在 root 管理器弹窗中允许。
3. 等待页面显示 **Root 已就绪 · UID=0 · 执行自检通过**。
4. 若失败，复制结果或日志。`UID=-1` 不是 Linux 中读取到的真实 UID，它表示旧版或检测链路没有读到有效结果，不能单凭它判断手机没有 root。

新版使用 libsu 6.0.0 的 RootService / AIDL，本地特权服务完成进程执行和二进制输出传输；命令超时、输出上限和取消由进程管理器处理。拒绝授权后重试会清理缓存的普通 shell。远端请求不会自行弹出 root 授权。

如果 root 服务仍不兼容当前 ROM，可手动选择并连接官方 Shizuku；实际 UID 会显示，不会静默切换。

shell：权限页选择并连接 Root 或 Shizuku；安全 → 开启“高级 shell”。shell 直接使用当前执行后端，不再另设后端。切换执行后端或权限变更会停止旧控制会话；截图、点击与输入不需要打开高级 shell。

## 界面与性能

底部导航：首页、权限、连接、安全、设置；统一卡片边距、表单宽度、按钮高度、开关对齐，支持浅色/深色及大字体换行。连接页通过 ViewModel + StateFlow 事件更新，证书/网络自检在后台执行，证书在内存缓存；输入框编辑不写盘，不触发密钥生成，保存时才验证并持久化。

## 文档

- [连接帮助](docs/HELP.md) · [连接二维码与自检](docs/CONNECTION_QR.md) · [Tailscale 分步说明](docs/TAILSCALE.md)
- [能力表](docs/CAPABILITIES.md) · [API](docs/API.md) · [OpenAPI](docs/openapi.json)
- [构建](docs/BUILD.md) · [签名约定](docs/SIGNING.md) · [双主题 UI 说明](docs/UI_DESIGN_0.5.md) · [Agent 初始化说明](docs/AGENT_START.md)

## 构建

固定工具链：JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、SDK 36 / Build Tools 36.0.0。完整 Gradle Wrapper 包含 SHA256，依赖固定版本，无私有路径依赖。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
./scripts/build.sh
pip install -c requirements.lock '.[test]'
pytest -q
```

源码：`android/`、`python/agentbridge/`、`.github/workflows/`、`docs/`；旧公网服务放在 `relay/`，当前不启用。push / PR / 手动运行会构建 debug APK；release 仅手动或 tag 触发。详见[构建](docs/BUILD.md)与[签名约定](docs/SIGNING.md)。

当前环境没有 KSU/ReKSU 真机或实际 Tailscale 网络。编译、单元测试及静态检查不等同于你的手机实机通过；请依据新页面显示的授权/服务/自检结果验收。
